"""Puts tools/pipeline on sys.path for the tests (run: python3 -m unittest discover tools/pipeline/tests)."""
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
PIPELINE = os.path.dirname(HERE)
if PIPELINE not in sys.path:
    sys.path.insert(0, PIPELINE)
