from __future__ import annotations

import os
from pathlib import Path
import tempfile

import onnx
from onnx import helper


def export_dynamic_int8(source: Path, output: Path, task: str, image_size: int) -> None:
    output.parent.mkdir(parents=True, exist_ok=True)

    with tempfile.TemporaryDirectory(prefix="onnx-export-") as work_dir:
        config_directory = Path(work_dir) / "ultralytics-config"
        config_directory.mkdir(parents=True, exist_ok=True)
        os.environ["YOLO_CONFIG_DIR"] = str(config_directory)
        import torch
        from onnxruntime.quantization import QuantType, quantize_dynamic
        from ultralytics import YOLO

        torch.set_num_threads(2)
        model = YOLO(str(source), task=task)
        exported_path = Path(
            model.export(
                format="onnx",
                imgsz=image_size,
                opset=17,
                dynamic=False,
                simplify=True,
                device="cpu",
                batch=1,
            )
        )
        if not exported_path.is_file():
            raise RuntimeError(f"Ultralytics did not create the ONNX export: {exported_path}")

        quantize_dynamic(
            model_input=str(exported_path),
            model_output=str(output),
            op_types_to_quantize=["Conv", "MatMul", "Gemm"],
            per_channel=True,
            reduce_range=False,
            weight_type=QuantType.QInt8,
            extra_options={"WeightSymmetric": True},
        )
        quantized = onnx.load(str(output))
        # Ultralytics adds the current wall-clock time to ONNX metadata. Remove
        # that field so identical pinned inputs and tools produce identical bytes.
        stable_metadata = [
            (entry.key, entry.value)
            for entry in quantized.metadata_props
            if entry.key != "date"
        ]
        del quantized.metadata_props[:]
        for key, value in stable_metadata:
            entry = quantized.metadata_props.add()
            entry.key = key
            entry.value = value
        onnx.checker.check_model(quantized)
        output.write_bytes(quantized.SerializeToString(deterministic=True))


def transpose_detect_output_to_channels_last(model_path: Path) -> None:
    model = onnx.load(str(model_path))
    if len(model.graph.output) != 1:
        raise RuntimeError("expected a single detector output")

    output = model.graph.output[0]
    dimensions = [dimension.dim_value for dimension in output.type.tensor_type.shape.dim]
    if len(dimensions) != 3 or dimensions[0] != 1 or dimensions[1] <= 0 or dimensions[2] <= 0:
        raise RuntimeError(f"unexpected detector output shape: {dimensions}")

    original_name = output.name
    channels_first_name = f"{original_name}_channels_first"
    producers = [node for node in model.graph.node if original_name in node.output]
    if len(producers) != 1:
        raise RuntimeError(f"expected one producer for detector output {original_name!r}")
    producer = producers[0]
    for index, name in enumerate(producer.output):
        if name == original_name:
            producer.output[index] = channels_first_name

    for value_info in model.graph.value_info:
        if value_info.name == original_name:
            value_info.name = channels_first_name

    model.graph.node.append(
        helper.make_node(
            "Transpose",
            [channels_first_name],
            [original_name],
            perm=[0, 2, 1],
            name="PanelDetectorChannelsLast",
        )
    )
    shape = output.type.tensor_type.shape
    del shape.dim[:]
    for dimension in (dimensions[0], dimensions[2], dimensions[1]):
        shape.dim.add().dim_value = dimension

    onnx.checker.check_model(model)
    model_path.write_bytes(model.SerializeToString(deterministic=True))
