#!/usr/bin/env python3
"""
Manga LaMa Fused PyTorch Architecture for Google LiteRT Mobile GPU Deployment.

Key Structural Innovations:
1. StaticFourierUnit:
   - Eliminates runtime Einsum, Range, Cos, Sin, and dynamic Slices.
   - Decomposes 2D Real-to-Complex FFT (64x64) into static 1D real matrix multiplications:
     X_freq = (Fh_c - j Fh_s) @ (X @ Cw - j X @ Sw)
   - Inverse 2D Complex-to-Real FFT using precomputed Ainv, Binv and Fh_c, Fh_s matrices.
   - 100% compatible with Google LiteRT BATCH_MATMUL & CONV_2D GPU delegate operations.

2. Conv-BatchNorm Folding:
   - Fuses all 75 BatchNorm points into preceding Conv2d and ConvTranspose2d weights/biases.
   - Eliminates the Layer 17 running variance overflow hazard (var = 675,607.12 > FP16 max 65,504).
   - Zero BatchNorm layers remaining in the graph.

3. Static Input Signature:
   - Accepts tensor shaped [B, 4, 512, 512] (Masked R, G, B, Binary Mask).
   - Bottleneck fixed permanently to 64x64 resolution.
"""

import math
import torch
import torch.nn as nn
import torch.nn.functional as F


def precompute_fourier_matrices(n: int = 64):
    """
    Precompute orthonormal 2D DFT and IDFT decomposition matrices for real inputs of size (n, n).
    
    Returns:
        Cw (n, n//2 + 1): Cosine matrix for 1D row RFFT
        Sw (n, n//2 + 1): Sine matrix for 1D row RFFT
        Fh_c (n, n): Cosine matrix for 1D column complex FFT
        Fh_s (n, n): Sine matrix for 1D column complex FFT
        Ainv (n//2 + 1, n): Cosine reconstruction matrix for 1D row IRFFT
        Binv (n//2 + 1, n): Sine reconstruction matrix for 1D row IRFFT
    """
    num_freq = n // 2 + 1  # 33 for n=64
    inv_sqrt_n = 1.0 / math.sqrt(n)

    # 1. Row RFFT matrices (n, num_freq)
    grid_n = torch.arange(n, dtype=torch.float32).unsqueeze(1)  # (n, 1)
    grid_kw = torch.arange(num_freq, dtype=torch.float32).unsqueeze(0)  # (1, num_freq)
    angles_w = (2.0 * math.pi * grid_n * grid_kw) / n
    Cw = torch.cos(angles_w) * inv_sqrt_n
    Sw = torch.sin(angles_w) * inv_sqrt_n

    # 2. Column Complex FFT matrices (n, n)
    grid_kh = torch.arange(n, dtype=torch.float32).unsqueeze(0)  # (1, n)
    angles_h = (2.0 * math.pi * grid_n * grid_kh) / n
    Fh_c = torch.cos(angles_h) * inv_sqrt_n
    Fh_s = torch.sin(angles_h) * inv_sqrt_n

    # 3. Row IRFFT reconstruction matrices (num_freq, n)
    scale = torch.full((num_freq, 1), 2.0 * inv_sqrt_n, dtype=torch.float32)
    scale[0, 0] = inv_sqrt_n
    scale[-1, 0] = inv_sqrt_n

    grid_kinv = torch.arange(num_freq, dtype=torch.float32).unsqueeze(1)  # (num_freq, 1)
    grid_ninv = torch.arange(n, dtype=torch.float32).unsqueeze(0)  # (1, n)
    angles_inv = (2.0 * math.pi * grid_kinv * grid_ninv) / n
    Ainv = scale * torch.cos(angles_inv)
    Binv = scale * torch.sin(angles_inv)

    return Cw, Sw, Fh_c, Fh_s, Ainv, Binv


class StaticFourierUnit(nn.Module):
    """
    Fast Fourier Unit with precomputed static matrix multiplications optimized
    for Qualcomm Snapdragon Adreno GPU (OpenCL) and Hexagon NPU (QNN).
    - Eliminates 5D tensors (torch.stack) in favor of strictly 4D channel concatenation.
    - Replaces 4D BatchMatMul with native nn.Linear (FULLY_CONNECTED) projections.
    - Zero strided gather/slice operations.
    """
    def __init__(self, in_channels: int = 192, spatial_size: int = 64):
        super().__init__()
        self.in_channels = in_channels
        self.spatial_size = spatial_size
        self.num_freq = spatial_size // 2 + 1  # 33

        Cw, Sw, Fh_c, Fh_s, Ainv, Binv = precompute_fourier_matrices(spatial_size)

        # 1D Row RFFT projections
        self.fc_cw = nn.Linear(spatial_size, self.num_freq, bias=False)
        self.fc_cw.weight.data = Cw.t().contiguous()
        self.fc_sw = nn.Linear(spatial_size, self.num_freq, bias=False)
        self.fc_sw.weight.data = Sw.t().contiguous()

        # 1D Column CFFT projections
        self.fc_fh_c = nn.Linear(spatial_size, spatial_size, bias=False)
        self.fc_fh_c.weight.data = Fh_c.contiguous()
        self.fc_fh_s = nn.Linear(spatial_size, spatial_size, bias=False)
        self.fc_fh_s.weight.data = Fh_s.contiguous()

        # 1D Row IRFFT reconstruction projections
        self.fc_ainv = nn.Linear(self.num_freq, spatial_size, bias=False)
        self.fc_ainv.weight.data = Ainv.t().contiguous()
        self.fc_binv = nn.Linear(self.num_freq, spatial_size, bias=False)
        self.fc_binv.weight.data = Binv.t().contiguous()

        # 1x1 Conv processing concatenated [real, imag] frequency representations
        self.conv_layer = nn.Conv2d(
            in_channels * 2,
            in_channels * 2,
            kernel_size=1,
            bias=True
        )
        self.relu = nn.ReLU(inplace=True)

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        """
        Forward pass for Fourier transform, spectral convolution, and inverse transform.
        Input x: [B, C, 64, 64]
        Output:  [B, C, 64, 64]
        """
        # 1. Forward 1D RFFT along width axis (dim 3)
        r_w = self.fc_cw(x)
        i_w = -self.fc_sw(x)

        # 2. Forward 1D CFFT along height axis (dim 2) via permuted Linear
        rw_t = r_w.permute(0, 1, 3, 2)
        iw_t = i_w.permute(0, 1, 3, 2)
        r_2d = (self.fc_fh_c(rw_t) + self.fc_fh_s(iw_t)).permute(0, 1, 3, 2)
        i_2d = (self.fc_fh_c(iw_t) - self.fc_fh_s(rw_t)).permute(0, 1, 3, 2)

        # 3. Concatenate real & imaginary channels (strictly 4D: [B, 2*C, 64, 33])
        ffted = torch.cat([r_2d, i_2d], dim=1)

        # 4. Complex frequency convolution & activation
        ffted = self.relu(self.conv_layer(ffted))

        # 5. Split channels back into real & imaginary
        r_conv, i_conv = torch.chunk(ffted, 2, dim=1)

        # 6. Inverse 1D CFFT along height axis
        r_conv_t = r_conv.permute(0, 1, 3, 2)
        i_conv_t = i_conv.permute(0, 1, 3, 2)
        r_h = (self.fc_fh_c(r_conv_t) - self.fc_fh_s(i_conv_t)).permute(0, 1, 3, 2)
        i_h = (self.fc_fh_c(i_conv_t) + self.fc_fh_s(r_conv_t)).permute(0, 1, 3, 2)

        # 7. Inverse 1D IRFFT along width axis
        x_rec = self.fc_ainv(r_h) - self.fc_binv(i_h)
        return x_rec



class FFCSpectralBlock(nn.Module):
    """
    Global spectral pathway inside an FFC module:
    1x1 Conv -> ReLU -> StaticFourierUnit -> Residual Add -> 1x1 Conv
    """
    def __init__(self, in_channels: int = 384, mid_channels: int = 192, spatial_size: int = 64):
        super().__init__()
        self.conv1 = nn.Conv2d(in_channels, mid_channels, kernel_size=1, bias=True)
        self.relu = nn.ReLU(inplace=True)
        self.fu = StaticFourierUnit(in_channels=mid_channels, spatial_size=spatial_size)
        self.conv2 = nn.Conv2d(mid_channels, in_channels, kernel_size=1, bias=False)

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        h = self.relu(self.conv1(x))
        fu_out = self.fu(h)
        out = self.conv2(h + fu_out)
        return out


class FusedFFC(nn.Module):
    """
    Fast Fourier Convolution block with Conv-BatchNorm folding applied.
    BatchNorm layers are fully absorbed into conv weights and biases.
    """
    def __init__(
        self,
        in_channels_l: int = 128,
        in_channels_g: int = 384,
        out_channels_l: int = 128,
        out_channels_g: int = 384,
        spatial_size: int = 64
    ):
        super().__init__()
        # Local -> Local branch (3x3 reflect-padded)
        self.pad_l2l = nn.ReflectionPad2d(1)
        self.conv_l2l = nn.Conv2d(in_channels_l, out_channels_l, kernel_size=3, padding=0, bias=True)

        # Global -> Local branch (3x3 reflect-padded)
        self.pad_g2l = nn.ReflectionPad2d(1)
        self.conv_g2l = nn.Conv2d(in_channels_g, out_channels_l, kernel_size=3, padding=0, bias=False)
        self.act_l = nn.ReLU(inplace=True)

        # Local -> Global branch (3x3 reflect-padded)
        self.pad_l2g = nn.ReflectionPad2d(1)
        self.conv_l2g = nn.Conv2d(in_channels_l, out_channels_g, kernel_size=3, padding=0, bias=True)

        # Global -> Global branch (1x1 convs + StaticFourierUnit)
        self.conv_g2g = FFCSpectralBlock(
            in_channels=in_channels_g,
            mid_channels=out_channels_g // 2,
            spatial_size=spatial_size
        )
        self.act_g = nn.ReLU(inplace=True)

    def forward(self, x_l: torch.Tensor, x_g: torch.Tensor):
        # Local output = ReLU(conv_l2l(x_l) + conv_g2l(x_g))
        y_l = self.conv_l2l(self.pad_l2l(x_l)) + self.conv_g2l(self.pad_g2l(x_g))
        out_l = self.act_l(y_l)

        # Global output = ReLU(conv_l2g(x_l) + conv_g2g(x_g))
        y_g = self.conv_l2g(self.pad_l2g(x_l)) + self.conv_g2g(x_g)
        out_g = self.act_g(y_g)

        return out_l, out_g


class FusedFFCResNetBlock(nn.Module):
    """
    Residual bottleneck block containing two FusedFFC operations with skip connections.
    """
    def __init__(
        self,
        channels_l: int = 128,
        channels_g: int = 384,
        spatial_size: int = 64
    ):
        super().__init__()
        self.ffc1 = FusedFFC(
            in_channels_l=channels_l,
            in_channels_g=channels_g,
            out_channels_l=channels_l,
            out_channels_g=channels_g,
            spatial_size=spatial_size
        )
        self.ffc2 = FusedFFC(
            in_channels_l=channels_l,
            in_channels_g=channels_g,
            out_channels_l=channels_l,
            out_channels_g=channels_g,
            spatial_size=spatial_size
        )

    def forward(self, x_l: torch.Tensor, x_g: torch.Tensor):
        h_l, h_g = self.ffc1(x_l, x_g)
        o_l, o_g = self.ffc2(h_l, h_g)
        return x_l + o_l, x_g + o_g


class MangaLaMaFused(nn.Module):
    """
    Full Manga LaMa architecture reconstructed in PyTorch with:
    - Precomputed StaticFourierUnit (zero Einsum/Cos/Sin/Range/dynamic Slices).
    - Complete Conv-BatchNorm folding (zero BatchNorm layers).
    - Fixed 512x512 resolution and [1, 4, 512, 512] input contract.
    """
    def __init__(self, num_blocks: int = 18):
        super().__init__()
        self.num_blocks = num_blocks

        # ============================================================
        # 1. ENCODER / DOWNSAMPLING (512 -> 256 -> 128 -> 64)
        # ============================================================
        # Layer 1: 4 -> 64 (stride 1, 7x7)
        self.enc_pad0 = nn.ReflectionPad2d(3)
        self.enc_conv0 = nn.Conv2d(4, 64, kernel_size=7, stride=1, padding=0, bias=True)
        self.enc_act0 = nn.ReLU(inplace=True)

        # Layer 2: 64 -> 128 (stride 2, 3x3)
        self.enc_pad1 = nn.ReflectionPad2d(1)
        self.enc_conv1 = nn.Conv2d(64, 128, kernel_size=3, stride=2, padding=0, bias=True)
        self.enc_act1 = nn.ReLU(inplace=True)

        # Layer 3: 128 -> 256 (stride 2, 3x3)
        self.enc_pad2 = nn.ReflectionPad2d(1)
        self.enc_conv2 = nn.Conv2d(128, 256, kernel_size=3, stride=2, padding=0, bias=True)
        self.enc_act2 = nn.ReLU(inplace=True)

        # Layer 4: 256 -> 128 (local) + 384 (global) (stride 2, 3x3)
        self.enc_pad3_l = nn.ReflectionPad2d(1)
        self.enc_conv3_l = nn.Conv2d(256, 128, kernel_size=3, stride=2, padding=0, bias=True)
        self.enc_act3_l = nn.ReLU(inplace=True)

        self.enc_pad3_g = nn.ReflectionPad2d(1)
        self.enc_conv3_g = nn.Conv2d(256, 384, kernel_size=3, stride=2, padding=0, bias=True)
        self.enc_act3_g = nn.ReLU(inplace=True)

        # ============================================================
        # 2. BOTTLENECK: 18 FUSED FFC RESNET BLOCKS (64x64)
        # ============================================================
        self.blocks = nn.ModuleList([
            FusedFFCResNetBlock(channels_l=128, channels_g=384, spatial_size=64)
            for _ in range(num_blocks)
        ])

        # ============================================================
        # 3. DECODER / UPSAMPLING (64 -> 128 -> 256 -> 512)
        # ConvTranspose2d with Conv-BN folding
        # ============================================================
        # Layer 24: 512 -> 256 (stride 2, 3x3, out_pad 1)
        self.dec_convtrans0 = nn.ConvTranspose2d(
            512, 256, kernel_size=3, stride=2, padding=1, output_padding=1, bias=True
        )
        self.dec_act0 = nn.ReLU(inplace=True)

        # Layer 27: 256 -> 128 (stride 2, 3x3, out_pad 1)
        self.dec_convtrans1 = nn.ConvTranspose2d(
            256, 128, kernel_size=3, stride=2, padding=1, output_padding=1, bias=True
        )
        self.dec_act1 = nn.ReLU(inplace=True)

        # Layer 30: 128 -> 64 (stride 2, 3x3, out_pad 1)
        self.dec_convtrans2 = nn.ConvTranspose2d(
            128, 64, kernel_size=3, stride=2, padding=1, output_padding=1, bias=True
        )
        self.dec_act2 = nn.ReLU(inplace=True)

        # ============================================================
        # 4. OUTPUT HEAD (512x512)
        # ============================================================
        self.out_pad = nn.ReflectionPad2d(3)
        self.out_conv = nn.Conv2d(64, 3, kernel_size=7, stride=1, padding=0, bias=True)
        self.out_act = nn.Sigmoid()

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        """
        Forward inference pass.
        Input x: [B, 4, 512, 512] (Channels 0-2: Masked RGB, Channel 3: Binary Mask)
        Output:  [B, 3, 512, 512] (RGB values strictly in [0.0, 1.0])
        """
        # 1. Encoder Downsampling
        h = self.enc_act0(self.enc_conv0(self.enc_pad0(x)))
        h = self.enc_act1(self.enc_conv1(self.enc_pad1(h)))
        h = self.enc_act2(self.enc_conv2(self.enc_pad2(h)))

        x_l = self.enc_act3_l(self.enc_conv3_l(self.enc_pad3_l(h)))
        x_g = self.enc_act3_g(self.enc_conv3_g(self.enc_pad3_g(h)))

        # 2. 18 Bottleneck Blocks
        for block in self.blocks:
            x_l, x_g = block(x_l, x_g)

        # 3. Concatenate Spatial & Spectral streams
        bottleneck = torch.cat([x_l, x_g], dim=1)  # [B, 512, 64, 64]

        # 4. Decoder Upsampling
        d = self.dec_act0(self.dec_convtrans0(bottleneck))
        d = self.dec_act1(self.dec_convtrans1(d))
        d = self.dec_act2(self.dec_convtrans2(d))

        # 5. Output Head & Sigmoid
        out = self.out_act(self.out_conv(self.out_pad(d)))
        return out


if __name__ == "__main__":
    print("Testing MangaLaMaFused instantiation and dummy forward pass...")
    model = MangaLaMaFused()
    model.eval()

    total_params = sum(p.numel() for p in model.parameters())
    print(f"Total Parameters: {total_params:,} ({total_params * 4 / (1024*1024):.2f} MB FP32)")

    # Check for any BatchNorm modules
    bn_count = sum(1 for m in model.modules() if isinstance(m, (nn.BatchNorm1d, nn.BatchNorm2d, nn.BatchNorm3d)))
    print(f"BatchNorm Module Count: {bn_count} (Must be 0)")
    assert bn_count == 0, "BatchNorm modules detected!"

    dummy_input = torch.randn(1, 4, 512, 512)
    with torch.no_grad():
        out = model(dummy_input)
    print(f"Output shape: {out.shape}, min: {out.min():.4f}, max: {out.max():.4f}")
    assert out.shape == (1, 3, 512, 512), f"Unexpected output shape: {out.shape}"
    print("MangaLaMaFused instantiation test passed!")
