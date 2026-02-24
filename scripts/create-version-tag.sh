#!/bin/bash

# Script to create a git tag based on the app's versionName

# Ensure we are in the project root
cd "$(dirname "$0")/.."

# Extract the primary versionName from the defaultConfig block
VERSION_NAME=$(grep -m 1 "versionName =" app-thunderbird/build.gradle.kts | sed -E 's/.*versionName = "(.*)".*/\1/')

if [ -z "$VERSION_NAME" ]; then
    echo "Error: Could not extract versionName from app-thunderbird/build.gradle.kts"
    exit 1
fi

TAG_NAME="v$VERSION_NAME"

# Check if the tag already exists
if git rev-parse "$TAG_NAME" >/dev/null 2>&1; then
    echo "Tag $TAG_NAME already exists."
else
    echo "Creating tag $TAG_NAME..."
    git tag -a "$TAG_NAME" -m "Release $TAG_NAME"
    echo "Tag $TAG_NAME created."
    echo "To push it and trigger the release workflow, run: git push origin $TAG_NAME"
fi
