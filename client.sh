#!/bin/bash

set -e

if [ "$#" -ne 1 ]; then
    echo "Usage: $0 <socket-path>"
    echo "Example: $0 /tmp/ssh-example.sock"
    exit 1
fi

SOCKET_PATH=$1

if [ ! -e "$SOCKET_PATH" ]; then
    echo "Error: Socket file does not exist at $SOCKET_PATH"
    exit 1
fi

# Prepare the message
MESSAGE_TYPE="\x02"  # 0x2 for Write
RESERVED="\x00\x00\x00"  # 3 bytes of zeros
CONTENT_LENGTH="\x00\x00\x00\x10"  # 0x10 for 16 bytes.

# Combine the message parts
FULL_MESSAGE="${MESSAGE_TYPE}${RESERVED}${CONTENT_LENGTH}AAA_AAA_AAA_AAA_"

echo "Connecting to socket at $SOCKET_PATH"

# Send the message and read response
{
    printf "$FULL_MESSAGE"
    sleep 1  # Give some time for the server to respond
} | nc -U "$SOCKET_PATH" | {
    # Read response and print as hex
    xxd -up -R auto
}

echo "Message sent and connection closed."