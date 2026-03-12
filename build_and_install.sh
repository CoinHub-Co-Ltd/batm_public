#!/bin/bash
dos2unix gradlew

set -e  # Exit on any error
set -x  # Print commands as they are run

# Step 1: Clean with debug output
bash ./gradlew clean -d

# Step 2: Build the project
bash ./gradlew build

# Step 3: Install the server_extensions_test
bash ./gradlew :server_extensions_test:install
