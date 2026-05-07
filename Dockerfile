FROM python:3.11-slim
WORKDIR /app
COPY cortex/backend/requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY cortex/backend/ .
CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8000"]
