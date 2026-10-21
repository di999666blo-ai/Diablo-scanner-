FROM python:3.11-slim
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
WORKDIR /srv
RUN apt-get update && apt-get install -y --no-install-recommends git     && rm -rf /var/lib/apt/lists/*
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
ARG KRONOS_UPSTREAM_SHA=67b630e67f6a18c9e9be918d9b4337c960db1e9a
RUN git clone https://github.com/shiyu-coder/Kronos.git /srv/kronos-upstream \
    && git -C /srv/kronos-upstream checkout "$KRONOS_UPSTREAM_SHA"
RUN pip install --no-cache-dir -r /srv/kronos-upstream/requirements.txt
ENV PYTHONPATH=/srv/kronos-upstream
COPY main.py .
EXPOSE 8080
CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8080"]
