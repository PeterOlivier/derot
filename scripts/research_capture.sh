#!/bin/bash
# Derot Research Capture Script
# Captures phone screen recording and logcat simultaneously with synchronized timestamps

set -e

TIMESTAMP=$(date +%Y%m%d_%H%M%S)
OUTPUT_DIR="./research_captures"
VIDEO_FILE="derot_research_$TIMESTAMP.mp4"
LOG_FILE="derot_research_$TIMESTAMP.log"

# Create output directory
mkdir -p "$OUTPUT_DIR"

echo "========================================"
echo "  DEROT RESEARCH CAPTURE"
echo "========================================"
echo ""
echo "Timestamp: $TIMESTAMP"
echo "Video: $OUTPUT_DIR/$VIDEO_FILE"
echo "Log:   $OUTPUT_DIR/$LOG_FILE"
echo ""

# Check ADB connection
if ! adb devices | grep -q "device$"; then
    echo "ERROR: No Android device connected via ADB"
    echo "Make sure:"
    echo "  1. USB debugging is enabled on your phone"
    echo "  2. Phone is connected via USB"
    echo "  3. You've authorized the computer on your phone"
    exit 1
fi

echo "Device connected: $(adb devices | grep device$ | cut -f1)"
echo ""

# Start instructions
echo "INSTRUCTIONS:"
echo "  1. Enable Research Mode in Derot app"
echo "  2. Open X/Twitter"
echo "  3. Perform your test actions"
echo "  4. Press Ctrl+C when done"
echo ""
echo "Recording will start in 3 seconds..."
sleep 3

echo ""
echo ">>> RECORDING STARTED <<<"
echo ""

# Start screen recording in background (on phone)
# Max 180 seconds per recording - will need to restart for longer sessions
adb shell "screenrecord --time-limit 180 /sdcard/$VIDEO_FILE" &
RECORD_PID=$!

# Start logcat capture in background
adb logcat -v time -s DerotResearch:* DerotBlocker:* > "$OUTPUT_DIR/$LOG_FILE" &
LOG_PID=$!

# Cleanup function
cleanup() {
    echo ""
    echo ">>> STOPPING CAPTURE <<<"
    echo ""

    # Kill local processes
    kill $LOG_PID 2>/dev/null || true

    # Stop screen recording on phone (it may have already stopped)
    adb shell "pkill -INT screenrecord" 2>/dev/null || true

    # Wait a moment for recording to finalize
    sleep 2

    # Pull video from phone
    echo "Pulling video from phone..."
    adb pull "/sdcard/$VIDEO_FILE" "$OUTPUT_DIR/" 2>/dev/null || echo "Warning: Could not pull video (may still be writing)"

    # Clean up file on phone
    adb shell "rm /sdcard/$VIDEO_FILE" 2>/dev/null || true

    echo ""
    echo "========================================"
    echo "  CAPTURE COMPLETE"
    echo "========================================"
    echo ""
    echo "Files saved to: $OUTPUT_DIR/"
    echo "  - $VIDEO_FILE (phone screen recording)"
    echo "  - $LOG_FILE (research logs)"
    echo ""
    echo "Log file also saved on phone at:"
    echo "  /Android/data/com.derot.videoblocker/files/research_logs/"
    echo ""

    # Show log preview
    if [ -f "$OUTPUT_DIR/$LOG_FILE" ]; then
        LINES=$(wc -l < "$OUTPUT_DIR/$LOG_FILE")
        echo "Log contains $LINES lines. Preview:"
        echo "----------------------------------------"
        head -20 "$OUTPUT_DIR/$LOG_FILE"
        echo "..."
        echo "----------------------------------------"
    fi
}

# Set up trap for Ctrl+C
trap cleanup EXIT INT TERM

# Wait for user to stop
echo "Press Ctrl+C to stop recording..."
wait $RECORD_PID 2>/dev/null || true
