FROM python:3.11-slim
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
WORKDIR /srv
RUN apt-get update && apt-get install -y --no-install-recommends git     && rm -rf /var/lib/apt/lists/*
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
RUN git clone --depth 1 --branch master https://github.com/shiyu-coder/Kronos.git /srv/kronos-upstream
RUN pip install --no-cache-dir -r /srv/kronos-upstream/requirements.txt
ENV PYTHONPATH=/srv/kronos-upstream
COPY main.py .
EXPOSE 8080
CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8080"]
