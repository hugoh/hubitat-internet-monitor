/*
 * Internet connection monitor
 */

/* groovylint-disable  CompileStatic, DuplicateStringLiteral, DuplicateNumberLiteral */

import groovy.transform.Field
import com.hubitat.app.DeviceWrapper

@Field static final String ICMP = 'ICMP'
@Field static final String HTTP = 'HTTP'

@Field static final String BOOL = 'bool'
@Field static final String NUMBER = 'number'
@Field static final String STRING = 'string'

@Field static final String HUBITAT_NAMESPACE = 'hubitat'
@Field static final String PRESENCE_DEVICE = 'Virtual Presence'
@Field static final String PRESENCE = 'presence'
@Field static final String PRESENT_TRUE = 'present'

@Field static final int ERROR_THRESHOLD_DEFAULT = 10000
@Field static final int HTTP_TIMEOUT_DEFAULT = 20
@Field static final float WARN_THRESHOLD = (float) 2 / 3 // groovylint-disable-line NoFloat
@Field static final int HTTP_CHECK_INTERVAL = 500
@Field static final int MAX_TRIES = 3

static final String version() { return '1.0.0' }

definition(
    name: 'Internet Connection Monitor',
    namespace: 'hugoh',
    author: 'Hugo Haas',
    description: 'Application monitoring the internet connection via HTTP & ICMP checks',
    iconUrl: '',
    iconX2Url: '',
    category: 'Utility',
    importUrl: 'https://github.com/hugoh/hubitat-internet-monitor/blob/master/internet-monitor.groovy',
    singleThreaded: true
)

@Field static final List DefaultCheckedUrls = [
    'http://www.gstatic.com/generate_204',
    'http://www.msftncsi.com/ncsi.txt',
    'http://detectportal.firefox.com/'
]
@Field static final List DefaultPingHosts = [
    '1.1.1.1',
    '8.8.8.8',
    '9.9.9.9'
]

preferences {
    section('Polling Intervals') {
        input('checkInterval', NUMBER, title: 'Polling interval in seconds when Internet is up',
            defaultValue: 300, required: true)
        input('checkIntervalWhenDown', NUMBER, title: 'Polling interval in seconds when Internet is down',
            defaultValue: 60, required: true)
    }
    section('Checked Endpoints') {
        input('checkedUrls', STRING, title: 'URLs to check via HTTP',
            description: 'Comma-separated list of URLs',
            defaultValue: DefaultCheckedUrls.join(','), required: true)
        input('pingHosts', STRING, title: 'Hosts to check via ping',
            description: 'Comma-separated list of IP addresses or hostnames',
            defaultValue: DefaultPingHosts.join(','), required: true)
    }
    section('Error Thresholds') {
        input('httpThreshold', NUMBER, title: 'Error latency threshold for HTTP checks (in ms)',
            defaultValue: ERROR_THRESHOLD_DEFAULT, required: true)
        input('pingThreshold', NUMBER, title: 'Error latency threshold for ping checks (in ms)',
            defaultValue: ERROR_THRESHOLD_DEFAULT)
    }
    section('Debugging') {
        input('logEnable', BOOL, title: 'Enable debug logging', defaultValue: false)
    }
}

/* ------------------------------------------------------------------------------------------------------- */

void installed() {
    unsubscribe()
    subscribe(location, 'systemStart', systemStart)
    initialize()
}

void updated() {
    installed()
}

void systemStart(evt) { // groovylint-disable-line NoDef, MethodParameterTypeRequired, UnusedMethodParameter
    initialize()
}

void uninstalled() {
    for (device in getChildDevices()) { // groovylint-disable-line UnnecessaryGetter
        deleteChildDevice(device.deviceNetworkId)
    }
}

void initialize() {
    log.info("${app.label} version ${version()} initializing")
    unschedule()
    initializeState()
    runIn(1, 'poll')
}

/* ------------------------------------------------------------------------------------------------------- */

void poll() {
    try {
        checkInternetIsUp()
    } finally {
        scheduleNextPoll()
    }
}

private void scheduleNextPoll() {
    nextRun = nextCheckIn()
    logDebug("Scheduling next check in ${nextRun} seconds")
    runIn(nextRun, 'poll')
}

private int nextCheckIn() {
    if (presenceDevice().currentValue(PRESENCE) == PRESENT_TRUE) {
        return settings.checkInterval
    } else { //groovylint-disable-line UnnecessaryElseStatement
        return settings.checkIntervalWhenDown
    }
}

private DeviceWrapper presenceDevice() {
    presenceSensorId = "internet-monitor:up_presence:${app.id}"
    presenceSensorName = "${app.label} Sensor"
    return getChildDevice(presenceSensorId) ?:
        addChildDevice(HUBITAT_NAMESPACE, PRESENCE_DEVICE, presenceSensorId,
                       [name: presenceSensorName, isComponent: true])
}

private void initializeState() {
    log.info("Initializing state for ${app.name} version ${version()}")
    state.clear()
    state.targets = [
        (HTTP): splitString(settings.checkedUrls, DefaultCheckedUrls),
        (ICMP): splitString(settings.pingHosts, DefaultPingHosts)
    ]
    final int initialIndex = -1
    state.targetIndex = [
        (HTTP): initialIndex,
        (ICMP): initialIndex
    ]
    state.errorThresholds = [
        (HTTP): positiveValue(settings.httpThreshold),
        (ICMP): positiveValue(settings.pingThreshold)
    ]
    state.httpTimeout = state.errorThresholds[HTTP] ? // This is in seconds for httpGet
        (int) Math.ceil(state.errorThresholds[HTTP] / 1000) :
        HTTP_TIMEOUT_DEFAULT
    state.warnThresholds = [:]
    for (t in [HTTP, ICMP]) {
        state.warnThresholds[t] = (int) Math.floor(state.errorThresholds[t] * WARN_THRESHOLD)
    }
    assert presenceDevice() != null
}

private void ensureValidState() {
    if (state.targets &&
        state.targetIndex &&
        state.errorThresholds &&
        state.httpTimeout) {
        return
    }
    // Something's missing
    initializeState()
}

private boolean httpTest(String uri) {
    boolean ret
    req = [
        'uri': uri,
        'timeout': state.httpTimeout,
        'textParser': true
    ]
    logDebug("Sending HTTP request ${req}")
    httpGet(req) { resp ->
        ret = resp.isSuccess() //groovylint-disable-line UnnecessaryGetter
    }
    logDebug("HTTP request ${uri} success: ${ret}")
    return ret
}

private boolean pingTest(String host) {
    logDebug("Sending ICMP ping to ${host}")
    hubitat.helper.NetworkUtils.PingData pingData = hubitat.helper.NetworkUtils.ping(host, 1)
    ret = pingData?.packetsReceived
    logDebug("Received ICMP ping response from ${host}: ${ret}")
    return ret
}

private boolean isTargetReachable(String target, String type) {
    logDebug("[${type}] Testing ${target} at most ${MAX_TRIES} times")
    boolean reachable = false
    for (i = 1; i <= MAX_TRIES; i++) {
        boolean reached
        start = now()
        try {
            if (type == HTTP) {
                reached = httpTest(target)
            } else if (type == ICMP) {
                reached = pingTest(target)
            } else {
                throw new IllegalArgumentException("Unsupported test type ${type}")
            }
            reachedIn = now() - start
        } catch (java.io.IOException ex) {
            log.error("Could not reach ${target}: ${ex.message}")
            reached = false
        }
        if (reached) {
            if (state.errorThresholds?."$type" > 0) {
                final String thresholdMsg = 'Target %s reached but latency exceeded threshold; took %dms'
                if (reachedIn > state.errorThresholds."$type") {
                    log.error(sprintf(thresholdMsg, target, reachedIn))
                    reached = false
                } else if (reachedIn > state.warnThresholds?."$type") {
                    log.warn(sprintf(thresholdMsg, target, reachedIn))
                }
            }
        }
        if (reached) {
            reachable = true
            break
        }
        if (type == HTTP) {
            logDebug("Retrying in ${HTTP_CHECK_INTERVAL}ms")
            pauseExecution(HTTP_CHECK_INTERVAL)
        }
    }
    if (reachable) {
        logDebug("[${type}] Reached ${target} in ${reachedIn}ms after ${i} tries")
    } else {
        log.error("[${type}] Could not reach ${target} after ${MAX_TRIES} tries")
    }
    logDebug("[${type}] Reached ${target}: ${reachable}")
    return reachable
}

private boolean runChecks(String type) {
    logDebug("Running ${type} checks")
    boolean isUp = false
    List targets = state.targets[type]
    int s = targets.size()
    for (j = 0; j < s; j++) {
        String target = targets.get(incTargetIndex(type))
        if (isTargetReachable(target, type)) {
            isUp = true
            break
        }
    }
    logDebug("${type} checks successful: ${isUp}")
    return isUp
}

private int incTargetIndex(String type) {
    int i = state.targetIndex[type] + 1
    i %= state.targets[type].size()
    state.targetIndex[type] = i
    return i
}

private boolean checkInternetIsUp() {
    logDebug('Checking for Internet connectivity')
    ensureValidState()
    boolean isUp
    isUp = runChecks(HTTP) ?: runChecks(ICMP)
    reportResults(isUp)
    return isUp
}

private void reportResults(boolean isUp) {
    if (isUp) {
        presenceDevice().arrived()
        log.info('Internet is up')
    } else {
        presenceDevice().departed()
        log.error('Internet is down: all tests failed')
    }
}

private int positiveValue(Long v) {
    return v == null ? 0 : Math.max(v, 0)
}

private List splitString(String commaSeparatedString, List defaultValue) {
    if (commaSeparatedString == null) {
        log.info("No settings value, using default ${defaultValue}")
        return defaultValue
    }
    return commaSeparatedString.split('[, ]+')
}

private void logDebug(String msg) {
    if (logEnable) {
        log.debug(msg)
    }
}
