#!/bin/bash
docker build -t predlock-extractor .
docker tag predlock-extractor rawanalkhalawi/predlock-extractor:latest
docker push rawanalkhalawi/predlock-extractor:latest
