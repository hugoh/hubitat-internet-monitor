/*
 * Internet connection sensor
 * based on Ping Presence Sensor by Ashish Chaudhari
 */

import groovy.transform.Field

metadata {
    definition (
        name: 'Internet Connection Sensor', 
        namespace: 'hugoh', 
        author: 'Hugo Haas',
        importUrl: 'https://github.com/hugoh/hubitat-internet-monitor/blob/release/internet-monitor.groovy'
    ) {
        capability 'PresenceSensor'
        capability 'Refresh'

        attribute 'lastUpdateTime', 'string'
        attribute 'lastReachedTarget', 'string'
        attribute 'lastReachedTime', 'string'
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
    input('pollingInterval', 'number', title: 'Polling interval in seconds when Internet is up',
        defaultValue: 300, required: true)
    input('pollingIntervalWhenDown', 'number', title: 'Polling interval in seconds when Internet is down',
        defaultValue: 60, required: true)
    input('checkedUrls', 'string', title: 'URLs to check via HTTP',
        description: 'Comma-separated list of URLs',
        defaultValue: DefaultCheckedUrls.join(','), required: true)
    input('pingHosts', 'string', title: 'Hosts to check via ping',
        description: 'Comma-separated list of IP addresses or hostnames',
        defaultValue: DefaultPingHosts.join(','), required: true)
    input('logEnable', 'bool', title: 'Enable debug logging', defaultValue: false)
}

@Field List CheckedUrls
@Field List PingHosts
@Field static final String ICMP = 'ICMP'
@Field static final String HTTP = 'HTTP'

def initialize() {
    log.info('Starting Internet checking loop')
    // Parse settings and use defaults in case of validation issue
    try {
        CheckedUrls = splitString(settings.checkedUrls)
    } catch (Exception ex) {
        CheckedUrls = DefaultCheckedUrls
    }
    try {
        PingHosts = splitString(settings.pingHosts)
    } catch (Exception ex) {
        PingHosts = DefaultPingHosts
    }
    // Start loop
    checkInternetLoop()
}

def refresh() {
    log.info('Stopping any pending checks')
    unschedule()
    initialize()
}

def updated() {
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
            log.error("Could not resolve ${target}")
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
        log.error("[${type}] Could not reach ${target}")
    }
    return reachable
}

boolean runChecks(List targets, String type) {
    logDebug("Running ${type} checks")
    boolean isUp = false
    Collections.shuffle(targets)
    for (String target: targets) {
        if (isTargetReachable(target, type)) {
            sendEvent(name: 'lastReachedTarget', value: target)
            isUp = true
            break
        }
    }
    return isUp
}

boolean checkInternetIsUp() {
    logDebug('Checking for Internet connectivity')
    boolean isUp
    isUp = runChecks(CheckedUrls, HTTP)
    if (!isUp) {
        isUp = runChecks(PingHosts, ICMP)
    }
    String now = new Date().toString()
    String presence
    if (isUp) {
        presence = 'present'
        sendEvent(name: 'lastReachedTime', value: now)
    } else {
        presence = 'not present'
    }
    sendEvent(name: 'presence', value: presence)
    sendEvent(name: 'lastUpdateTime', value: now)
    return isUp
}

void checkInternetLoop() {
    boolean isUp = checkInternetIsUp()
    if (isUp)
        nextRun = settings.pollingInterval
    else
        nextRun = settings.pollingIntervalWhenDown
    logDebug("Scheduling next check in ${nextRun} seconds")
    runIn(nextRun, "checkInternetLoop")
}

// --------------------------------------------------------------------------

List splitString(String commaSeparatedString) {
    String[] items = commaSeparatedString.split('[, ]+')
    ArrayList<String> list = new ArrayList<String>(items.length)
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
