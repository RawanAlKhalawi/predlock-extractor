#!/bin/bash
docker build --no-cache -t rawanalkhalawi/predlock-extractor:latest .
docker tag predlock-extractor rawanalkhalawi/predlock-extractor:latest
docker push rawanalkhalawi/predlock-extractor:latest
