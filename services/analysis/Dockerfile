FROM python:3.12.14-slim
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir --requirement requirements.txt
COPY analysis_app ./analysis_app
RUN addgroup --system --gid 10002 analysis && adduser --system --uid 10002 --ingroup analysis analysis
USER 10002
EXPOSE 8000
HEALTHCHECK --interval=15s --timeout=3s --retries=3 CMD python -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8000/health/ready', timeout=2).read()" || exit 1
CMD ["uvicorn", "analysis_app.main:app", "--host", "0.0.0.0", "--port", "8000", "--no-server-header"]
