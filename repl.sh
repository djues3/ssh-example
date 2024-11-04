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

function parse_response() {
    # Read hex dump into variables
    read -r hex rest

    # Extract type (first byte)
    type_hex=${hex:0:2}
    type_num=$((16#$type_hex))
    
    # Map type number to string
    case $type_num in
        1) type_str="OK" ;;
        2) type_str="WRITE" ;;
        3) type_str="CLEAR" ;;
        4) type_str="ERROR" ;;
        5) type_str="PING" ;;
        *) type_str="UNKNOWN" ;;
    esac

    # Skip 3 reserved bytes (6 hex chars)
    # Extract content length (next 4 bytes, 8 hex chars)
    length_hex=${hex:8:8}
    # Convert from little endian
    length_hex=$(echo $length_hex | sed 's/\(..\)\(..\)\(..\)\(..\)/\4\3\2\1/')
    content_length=$((16#$length_hex))

    # Extract content if any
    content=""
    if [ $content_length -gt 0 ]; then
        content_hex=${hex:16:$((content_length * 2))}
        content=$(echo $content_hex | xxd -r -p)
    fi

    echo "Response: ${type_num}(${type_str}) ${content_length} ${content}"
}

function send_message() {
    local message_type=$1
    local content="$2"
    local content_length=$(printf "%02x" ${#content})

    # Pad content length to 4 bytes
    while [ ${#content_length} -lt 8 ]; do
        content_length="0${content_length}"
    done

    # Convert content length to little endian hex bytes
    content_length=$(echo $content_length | sed 's/\(..\)/\\x\1/g')

    # Prepare message parts
    local msg_type_hex=$(printf "%02x" $message_type)
    MESSAGE_TYPE="\x${msg_type_hex}"
    RESERVED="\x00\x00\x00"

    # Combine message
    FULL_MESSAGE="${MESSAGE_TYPE}${RESERVED}${content_length}${content}"

    # Send and receive
    printf "$FULL_MESSAGE" | socat - UNIX-CONNECT:"$SOCKET_PATH" | xxd -p | parse_response
}

while true; do
    echo -e "\nAvailable commands:"
    echo "1) Write"
    echo "2) Clear"
    echo "3) Ping"
    echo "4) Exit"
    read -p "Select operation (1-4): " choice

    case $choice in
        1)
            read -p "Enter message to write: " message
            send_message 2 "$message"  # 0x02 for Write
            ;;
        2)
            send_message 3 ""  # 0x03 for Clear
            ;;
        3)
            send_message 5 ""  # 0x05 for Ping
            ;;
        4)
            echo "Exiting..."
            exit 0
            ;;
        *)
            echo "Invalid choice!"
            ;;
    esac
done