#!/bin/sh
# Builds and runs host_packets_test.c on the host. CC defaults to gcc.
set -e
cd "$(dirname "$0")"
C=../moonlight-common-c
OUT=${TMPDIR:-/tmp}/host_packets_test
"${CC:-gcc}" -std=c11 -O1 -Wall -Wextra -Wno-unused-parameter -DHAS_SOCKLEN_T -DNDEBUG \
    -I$C/src -I$C/enet/include -I$C/nanors -I$C/nanors/deps -I$C/nanors/deps/obl \
    host_packets_test.c $C/src/InputStream.c $C/src/ByteBuffer.c \
    -o "$OUT"
"$OUT"
