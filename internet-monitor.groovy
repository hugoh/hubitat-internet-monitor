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

@Field static final String BOOL = 'bool'
@Field static final String NUMBER = 'number'
@Field static final String STRING = 'string'

@Field static final String PRESENCE = 'presence'
@Field static final String PRESENT_TRUE = 'present'
@Field static final String PRESENT_FALSE = 'not present'

public static final String version() { return "0.1.20230530" }

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
    input('logEnable', BOOL, title: 'Enable debug logging', defaultValue: false)
}

void initialize() {
    log.info("Starting Internet checking loop - version ${version()}")
    // Parse settings and use defaults in case of validation issue
    checkedUrls = splitString(settings.checkedUrls, DefaultCheckedUrls)
    pingHosts = splitString(settings.pingHosts, DefaultPingHosts)
    // Start loop
    checkInternetLoop([checkedUrls: checkedUrls, pingHosts: pingHosts])
}

void refresh() {
    log.info('Canceling any pending scheduled tasks')
    unschedule()
    initialize()
}

void updated() {
    refresh()
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
        try {
            if (type == HTTP) {
                reached = get(target)
            } else if (type == ICMP) {
                reached = ping(target)
            } else {
                throw new Exception("Unsupported test type ${type}")
            }
        } catch (java.net.UnknownHostException ex) {
            log.warn("Could not resolve ${target}: ${ex.message}")
            reached = false
        }
        if (reached) {
            reachable = true
            break
        }
        pauseExecution(1000)
    }
    if (reachable) {
        logDebug("[${type}] Reached ${target} after ${i} tries")
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

boolean checkInternetIsUp(List checkedUrls, List pingHosts) {
    logDebug('Checking for Internet connectivity')
    boolean isUp
    isUp = runChecks(checkedUrls, HTTP)
    isUp = isUp ?: runChecks(pingHosts, ICMP)
    String now = new Date() // groovylint-disable-line NoJavaUtilDate
    String presence
    if (isUp) {
        presence = PRESENT_TRUE
        sendEvent(name: LAST_REACHED_TIME, value: now)
    } else {
        presence = PRESENT_FALSE
    }
    sendEvent(name: PRESENCE, value: presence)
    sendEvent(name: LAST_UPDATE_TIME, value: now)
    return isUp
}

void checkInternetLoop(Map data) {
    List checkedUrls = data.checkedUrls
    List pingHosts = data.pingHosts
    boolean isUp = checkInternetIsUp(checkedUrls, pingHosts)
    nextRun = isUp ? settings.pollingInterval : settings.pollingIntervalWhenDown
    logDebug("Scheduling next check in ${nextRun} seconds")
    runIn(nextRun, 'checkInternetLoop', [data: [checkedUrls: checkedUrls, pingHosts: pingHosts]])
}

// --------------------------------------------------------------------------

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
