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
        attribute 'lastReachedIp', 'string'
        attribute 'lastReachedTime', 'string'
    }   
}

@Field static final List DefaultPingHosts = ['1.1.1.1', '8.8.8.8', '9.9.9.9']

preferences {
    input('pollingInterval', 'number', title: 'Polling interval in seconds when Internet is up',
        defaultValue: 300, required: true)
    input('pollingIntervalWhenDown', 'number', title: 'Polling interval in seconds when Internet is down',
        defaultValue: 60, required: true)
    input('pingHosts', 'string', title: 'Hosts to monitor via ping',
        description: 'Comma-separated list of IP addresses or hostnames',
        defaultValue: DefaultPingHosts.join(','), required: true)
    input('logEnable', 'bool', title: 'Enable debug logging', defaultValue: false)
}

@Field List PingHosts

def initialize() {
    log.info('Starting Internet checking loop')
    PingHosts = splitString(settings.pingHosts)
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

boolean ping(String ip) {
    hubitat.helper.NetworkUtils.PingData pingData = hubitat.helper.NetworkUtils.ping(ip, 1)
    return pingData?.packetsReceived
}

boolean isHostReachableByICMP(String ip) {
    final int maxTries = 3
    logDebug("[ICMP] Testing ${ip} at most ${maxTries} times")
    boolean reachable = false
    int i
    for (i = 1; i <= maxTries; i++) {
        if (ping(ip)) {
            reachable = true
            break
        }
        pauseExecution(1000)
    }
    logDebug("[ICMP] Reachable = ${reachable} for ${ip}; packets sent: ${i}")
    return reachable
}

boolean checkInternetIsUp() {
    logDebug('Checking for Internet connectivity')
    boolean isUp = false
    Collections.shuffle(PingHosts)
    for (String target: PingHosts) {
        if (isHostReachableByICMP(target)) {
            sendEvent(name: 'lastReachedIp', value: target)
            isUp = true
            break
        }
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
