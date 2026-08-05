#!/bin/bash

set -e

echo "Initializing LocalStack resources..."

# Create S3 bucket for Orc file storage.
echo "Creating S3 bucket: orc-files"
awslocal s3 mb s3://orc-files
echo "S3 bucket created successfully"

# CORS so the browser can PUT/GET directly to S3 from the app origin (presigned URLs).
echo "Configuring CORS on orc-files..."
awslocal s3api put-bucket-cors \
  --bucket orc-files \
  --cors-configuration '{
    "CORSRules": [
      {
        "AllowedOrigins": ["http://localhost:8080", "http://localhost:8081"],
        "AllowedMethods": ["GET", "PUT", "POST", "HEAD"],
        "AllowedHeaders": ["*"],
        "ExposeHeaders": ["ETag"],
        "MaxAgeSeconds": 3000
      }
    ]
  }'
echo "CORS configured"

echo "LocalStack initialization complete!"
