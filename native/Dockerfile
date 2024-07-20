# Use the smallest Debian base image
FROM debian:bookworm-slim

# Install any essential dependencies your "app" needs (if any)
# Example:
# RUN apt-get update && apt-get install -y \
#     libssl-dev \
#     # ... other dependencies ... \
# && rm -rf /var/lib/apt/lists/*

# Create a directory for your application
WORKDIR /app

# Copy your application executable into the container
COPY app .
COPY logging.properties .

# Make your application executable (if necessary)
RUN chmod +x /app/app

# Set the entrypoint to directly run your application
ENTRYPOINT ["/app/app"]

