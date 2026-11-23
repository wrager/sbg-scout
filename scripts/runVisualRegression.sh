#!/bin/sh
set -eu

repetitions=${VR_REPETITIONS:-1}
case "$repetitions" in
  1|2|3|4|5|6|7|8|9|10) ;;
  *) echo 'VR_REPETITIONS must be an integer from 1 to 10.' >&2; exit 2 ;;
esac
if [ "$repetitions" -gt 1 ] && [ -z "${VR_TEST_CLASS:-}" ]; then
  echo 'Repeated investigations require VR_TEST_CLASS.' >&2
  exit 2
fi

attempt=1
while [ "$attempt" -le "$repetitions" ]; do
  echo "Visual regression attempt $attempt/$repetitions"
  set -- visualRegressionTest
  if [ -n "${VR_TEST_CLASS:-}" ]; then
    set -- "$@" "-Pandroid.testInstrumentationRunnerArguments.class=$VR_TEST_CLASS"
  fi
  status=0
  ./gradlew "$@" --stacktrace --no-daemon || status=$?

  output="build/visual-regression-runs/attempt-$attempt"
  mkdir -p "$output"
  for path in app/build/outputs/connected_android_test_additional_output \
      app/build/outputs/androidTest-results app/build/reports/visual-regression \
      app/build/reports/androidTests; do
    if [ -d "$path" ]; then cp -R "$path" "$output/"; fi
  done
  log_status=0
  adb logcat -d -v threadtime > "$output/logcat.txt" || log_status=$?
  if [ "$status" -ne 0 ]; then exit "$status"; fi
  if [ "$log_status" -ne 0 ]; then exit "$log_status"; fi
  attempt=$((attempt + 1))
done
