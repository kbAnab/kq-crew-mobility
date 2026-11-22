FROM python:3.11-slim

ENV PYTHONDONTWRITEBYTECODE=1
ENV PYTHONUNBUFFERED=1

WORKDIR /app/crew-ml

COPY crew-recomendation-model/crew-ml/requirements.txt ./requirements.txt
RUN pip install --no-cache-dir -r requirements.txt

COPY crew-recomendation-model/crew-ml/ ./
COPY crew-recomendation-model/models/crew_recommender.pkl /app/models/crew_recommender.pkl

CMD ["sh", "-c", "uvicorn api:app --host 0.0.0.0 --port ${PORT:-8080}"]