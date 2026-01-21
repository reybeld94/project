#!/bin/bash
# Script to run Alembic migrations inside the Docker container

echo "Running Alembic migrations..."
cd /app && alembic upgrade head
echo "Migrations completed!"
