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
@Field static final String LAST_UPDATE_EXCEEDED_THRESHOLD = 'lastUpdateExceededThreshold'

@Field static final String BOOL = 'bool'
@Field static final String NUMBER = 'number'
@Field static final String STRING = 'string'

@Field static final String PRESENCE = 'presence'
@Field static final String PRESENT_TRUE = 'present'
@Field static final String PRESENT_FALSE = 'not present'

public static final String version() { return '0.5.0' }

metadata {
    definition(
        name: 'Internet Connection Sensor',
        namespace: 'hugoh',
        author: 'Hugo Haas',
        importUrl: 'https://github.com/hugoh/hubitat-internet-monitor/blob/master/internet-monitor.groovy'
    ) {
        capability 'PresenceSensor'
        capability 'Refresh'

        attribute LAST_REACHED_TARGET, STRING
        attribute LAST_REACHED_TIME, STRING
        attribute LAST_UPDATE_TIME, STRING
        attribute LAST_UPDATE_LATENCY, NUMBER
        attribute LAST_UPDATE_EXCEEDED_THRESHOLD, BOOL
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
    input('pollingInterval', NUMBER, title: 'Polling interval in seconds when Internet is up',
        defaultValue: 300, required: true)
    input('pollingIntervalWhenDown', NUMBER, title: 'Polling interval in seconds when Internet is down',
        defaultValue: 60, required: true)
    input('checkedUrls', STRING, title: 'URLs to check via HTTP',
        description: 'Comma-separated list of URLs',
        defaultValue: DefaultCheckedUrls.join(','), required: true)
    input('pingHosts', STRING, title: 'Hosts to check via ping',
        description: 'Comma-separated list of IP addresses or hostnames',
        defaultValue: DefaultPingHosts.join(','), required: true)
    input('httpThreshold', NUMBER, title: 'Warn latency threshold for HTTP checks')
    input('pingThreshold', NUMBER, title: 'Warn latency threshold for ping checks')
    input('logEnable', BOOL, title: 'Enable debug logging', defaultValue: false)
}

void initialize() {
    log.info("Starting Internet checking loop - version ${version()}")
    // Parse settings and use defaults in case of validation issue
    state.checkedUrls = splitString(settings.checkedUrls, DefaultCheckedUrls)
    state.pingHosts = splitString(settings.pingHosts, DefaultPingHosts)
    state.warnThresholds = [
        (HTTP): positiveValue(settings.httpThreshold),
        (ICMP): positiveValue(settings.pingThreshold)
    ]
    // Start loop
    checkInternetLoop()
}

void refresh() {
    log.info('Manual refresh')
    checkInternetIsUp()
}

void updated() {
    log.info('Canceling any pending scheduled tasks')
    unschedule()
    initialize()
}

boolean get(String url) {
    boolean ret
    httpGet(url) { resp ->
        ret = resp.isSuccess()
    }
    return ret
}

boolean ping(String host) {
    hubitat.helper.NetworkUtils.PingData pingData = hubitat.helper.NetworkUtils.ping(host, 1)
    return pingData?.packetsReceived
}

boolean isTargetReachable(String target, String type) {
    final int maxTries = 3
    logDebug("[${type}] Testing ${target} at most ${maxTries} times")
    boolean reachable = false
    int i
    for (i = 1; i <= maxTries; i++) {
        boolean reached
        start = now()
        try {
            if (type == HTTP) {
                reached = get(target)
            } else if (type == ICMP) {
                reached = ping(target)
            } else {
                throw new Exception("Unsupported test type ${type}")
            }
            end = now()
        } catch (java.io.IOException ex) {
            log.warn("Could not reach ${target}: ${ex.message}")
            reached = false
        }
        if (reached) {
            reachable = true
            break
        }
        pauseExecution(1000)
    }
    if (reachable) {
        reachedIn = end - start
        boolean reachThresholdExceeded = false
        sendEvent(name: LAST_UPDATE_LATENCY, value: reachedIn)
        logDebug("[${type}] Reached ${target} in ${reachedIn}ms after ${i} tries")
        if (state.warnThresholds[type] > 0 && reachedIn >= state.warnThresholds[type]) {
            reachThresholdExceeded = true
            log.warn("Target ${target} reached but latency exceeded threshold; took ${reachedIn}ms")
        }
        sendEvent(name: LAST_UPDATE_EXCEEDED_THRESHOLD, value: reachThresholdExceeded)
    } else {
        log.warn("[${type}] Could not reach ${target} after ${maxTries} tries")
    }
    return reachable
}

boolean runChecks(List targets, String type) {
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
    return isUp
}

boolean checkInternetIsUp() {
    logDebug('Checking for Internet connectivity')
    boolean isUp
    isUp = runChecks(state.checkedUrls, HTTP)
    isUp = isUp ?: runChecks(state.pingHosts, ICMP)
    String now = new Date() // groovylint-disable-line NoJavaUtilDate
    String presence
    if (isUp) {
        presence = PRESENT_TRUE
        sendEvent(name: LAST_REACHED_TIME, value: now)
        log.info('Internet is up')
    } else {
        presence = PRESENT_FALSE
        log.warn('Internet is down: all tests failed')
    }
    sendEvent(name: PRESENCE, value: presence)
    sendEvent(name: LAST_UPDATE_TIME, value: now)
    return isUp
}

void checkInternetLoop() {
    boolean isUp = checkInternetIsUp()
    nextRun = isUp ? settings.pollingInterval : settings.pollingIntervalWhenDown
    logDebug("Scheduling next check in ${nextRun} seconds")
    runIn(nextRun, 'checkInternetLoop')
}

// --------------------------------------------------------------------------

int positiveValue(v) {
    return v == null ? 0 : Math.max(v, 0)
}

List splitString(String commaSeparatedString, List defaultValue) {
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

void logDebug(String msg) {
    if (logEnable) {
        log.debug(msg)
    }
}
