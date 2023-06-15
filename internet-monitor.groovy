/*
 * Internet connection sensor
 * based on Ping Presence Sensor by Ashish Chaudhari
 */

import groovy.transform.Field

@Field static final String ICMP = 'ICMP'
@Field static final String HTTP = 'HTTP'
@Field static final String LAST_REACHED_TARGET = 'lastReachedTarget'
@Field static final String LAST_REACHED_TIME = 'lastReachedTime'
@Field static final String LAST_UPDATE_TIME = 'lastUpdateTime'
@Field static final String LAST_UPDATE_LATENCY = 'lastUpdateLatency'

@Field static final String BOOL = 'bool'
@Field static final String NUMBER = 'number'
@Field static final String STRING = 'string'

@Field static final String PRESENCE = 'presence'
@Field static final String PRESENT_TRUE = 'present'
@Field static final String PRESENT_FALSE = 'not present'

@Field static final int ERROR_THRESHOLD_DEFAULT = 10000
@Field static final int HTTP_TIMEOUT_DEFAULT = 20
@Field static final BigDecimal WARN_THRESHOLD = 2 / 3
@Field static final int HTTP_CHECK_INTERVAL = 500
@Field static final int MAX_TRIES = 3

static final String version() { return '0.10.0' }

metadata {
    definition(
        name: 'Internet Connection Sensor',
        namespace: 'hugoh',
        author: 'Hugo Haas',
        importUrl: 'https://github.com/hugoh/hubitat-internet-monitor/blob/master/internet-monitor.groovy',
        singleThreaded: true
    ) {
        capability 'PresenceSensor'
        capability 'HealthCheck'
        capability 'Refresh'

        attribute LAST_REACHED_TARGET, STRING
        attribute LAST_REACHED_TIME, STRING
        attribute LAST_UPDATE_TIME, STRING
        attribute LAST_UPDATE_LATENCY, NUMBER
    }
}

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
    input('checkInterval', NUMBER, title: 'Polling interval in seconds when Internet is up',
        defaultValue: 300, required: true)
    input('checkIntervalWhenDown', NUMBER, title: 'Polling interval in seconds when Internet is down',
        defaultValue: 60, required: true)
    input('checkedUrls', STRING, title: 'URLs to check via HTTP',
        description: 'Comma-separated list of URLs',
        defaultValue: DefaultCheckedUrls.join(','), required: true)
    input('pingHosts', STRING, title: 'Hosts to check via ping',
        description: 'Comma-separated list of IP addresses or hostnames',
        defaultValue: DefaultPingHosts.join(','), required: true)
    input('httpThreshold', NUMBER, title: 'Error latency threshold for HTTP checks (in ms)',
        defaultValue: ERROR_THRESHOLD_DEFAULT, required: true)
    input('pingThreshold', NUMBER, title: 'Error latency threshold for ping checks (in ms)',
        defaultValue: ERROR_THRESHOLD_DEFAULT)
    input('logEnable', BOOL, title: 'Enable debug logging', defaultValue: false)
}

void initialize() {
    log.info("Starting Internet checking loop - version ${version()}")
    initializeState()
    poll()
}

void ping() {
    checkInternetIsUp()
}

void refresh() {
    updated()
}

void updated() {
    log.info('Canceling any pending scheduled tasks')
    unschedule()
    initialize()
}

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
    if (device.currentValue(PRESENCE) == PRESENT_TRUE) {
        return settings.checkInterval
    } else {
        return settings.checkIntervalWhenDown
    }
}

private void initializeState() {
    log.info("Initializing state for version ${version()}")
    state.checkedUrls = splitString(settings.checkedUrls, DefaultCheckedUrls)
    state.pingHosts = splitString(settings.pingHosts, DefaultPingHosts)
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
}

private void ensureValidState() {
    if (state.checkedUrls &&
        state.pingHosts &&
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
        ret = resp.isSuccess()
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
    int i
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
        sendEvent(name: LAST_UPDATE_LATENCY, value: reachedIn)
        logDebug("[${type}] Reached ${target} in ${reachedIn}ms after ${i} tries")
    } else {
        log.error("[${type}] Could not reach ${target} after ${MAX_TRIES} tries")
    }
    logDebug("[${type}] Reached ${target}: ${reachable}")
    return reachable
}

private boolean runChecks(List targets, String type) {
    logDebug("Running ${type} checks")
    boolean isUp = false
    for (String target: targets) {
        if (isTargetReachable(target, type)) {
            sendEvent(name: LAST_REACHED_TARGET, value: target)
            isUp = true
            break
        }
    }
    Collections.rotate(targets, 1)
    logDebug("${type} checks successful: ${isUp}")
    return isUp
}

private boolean checkInternetIsUp() {
    logDebug('Checking for Internet connectivity')
    ensureValidState()
    boolean isUp
    isUp = runChecks(state.checkedUrls, HTTP)
    isUp = isUp ?: runChecks(state.pingHosts, ICMP)
    reportResults(isUp)
    return isUp
}

private void reportResults(boolean isUp) {
    String now = new Date() // groovylint-disable-line NoJavaUtilDate
    String presence
    if (isUp) {
        presence = PRESENT_TRUE
        sendEvent(name: LAST_REACHED_TIME, value: now)
        log.info('Internet is up')
    } else {
        presence = PRESENT_FALSE
        log.error('Internet is down: all tests failed')
    }
    sendEvent(name: PRESENCE, value: presence)
    sendEvent(name: LAST_UPDATE_TIME, value: now)
}

// --------------------------------------------------------------------------

private BigDecimal positiveValue(BigDecimal v) {
    return v == null ? 0 : Math.max(v, (double) 0)
}

private List splitString(String commaSeparatedString, List defaultValue) {
    if (commaSeparatedString == null) {
        log.info("No settings value, using default ${defaultValue}")
        return defaultValue
    }
    String[] items = commaSeparatedString.split('[, ]+')
    List<String> list = new ArrayList<String>(items.length)
    for (String i: items) {
        list.add(i)
    }
    return list
}

private void logDebug(String msg) {
    if (logEnable) {
        log.debug(msg)
    }
}
