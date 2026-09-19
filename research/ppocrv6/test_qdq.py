#!/usr/bin/env python3
"""Entry point for the calibration-based QDQ experiment."""
from test_int8 import main

if __name__ == "__main__":
    raise SystemExit(main(forced_mode="qdq"))

