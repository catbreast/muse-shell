#!/bin/bash
# MuseShell container phase-2: install RapidOCR (on-device OCR, images never leave the phone).
exec > /root/setup2.log 2>&1
set -x
pip3 install --break-system-packages -i https://mirrors.tuna.tsinghua.edu.cn/pypi/web/simple \
  rapidocr-onnxruntime
python3 -c "from rapidocr_onnxruntime import RapidOCR; RapidOCR(); print('OCR_READY')"
echo SETUP2_DONE
