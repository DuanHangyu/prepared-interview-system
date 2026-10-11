// Local diagnostic policy only. The caller still owns private paths, JFC
// validation, process lifecycle and strict post-recording event verification.
function safePath(value, suffix) {
  if(typeof value!=='string' || !/^\/(?:[A-Za-z0-9_.-]+\/)*[A-Za-z0-9_.-]+$/.test(value)
    || !value.endsWith(suffix) || value.split('/').some(part=>part==='.' || part==='..')) {
    throw new Error('UNSAFE_LATENCY_PATH');
  }
  return value;
}
export function privateLatencyOptions(settings, recording) {
  safePath(settings,'.jfc'); safePath(recording,'.jfr');
  return Object.freeze([
    '-Dio.lettuce.core.jfr=false',
    '-XX:FlightRecorderOptions=memorysize=8m,stackdepth=64',
    '-XX:StartFlightRecording=name=PreparedLatency,settings='+settings+',filename='+recording+',maxsize=32m,maxage=20m,dumponexit=true'
  ]);
}
