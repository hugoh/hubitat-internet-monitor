/*
 * Internet connection sensor
 * based on Ping Presence Sensor by Ashish Chaudhari
 */

metadata {
    definition (
		name: 'Internet Connection Sensor', 
		namespace: 'hugoh', 
		author: 'Hugo Haas',
	) {
        capability 'PresenceSensor'
        capability 'Refresh'

        attribute 'lastUpdateTime', 'string'
    }   
}

preferences {
    input('pollingInterval', 'number', title: 'Polling interval in seconds when Internet is up',
        defaultValue: 300, required: true)
    input('pollingIntervalWhenDown', 'number', title: 'Polling interval in seconds when Internet is down',
        defaultValue: 60, required: true)
    input('ipAddresses', 'string', title: 'IP addresses to monitor via ping',
        description: 'Comma-separated list of IP addresses',
        defaultValue:'1.1.1.1,8.8.8.8', required: true)
    input('logEnable', 'bool', title: 'Enable debug logging', defaultValue: false)
}

def initialize() {
    log.info('Starting Internet checking loop')
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

boolean isHostReachable(String ip) {
    final int maxTries = 3
    logDebug("Testing ${ip} at most ${maxTries} times")
    boolean reachable = false
    int i
    for (i = 1; i <= maxTries; i++) {
        hubitat.helper.NetworkUtils.PingData pingData = hubitat.helper.NetworkUtils.ping(ip, 1)
        if (pingData?.packetsReceived) {
            reachable = true
            break
        }
        pauseExecution(1000)
    }
    logDebug("Reachable = ${reachable} for ${ip}; packets sent: ${i}")
    return reachable
}

boolean checkInternetIsUp() {
    logDebug('Checking for Internet connectivity')
    boolean isUp = false
    for (String target: settings.ipAddresses.split('[, ]+')) {
        if (isHostReachable(target)) {
            isUp = true
            break
        }
    }
    if (isUp)
        sendEvent(name:"presence", value:"present")
    else
        sendEvent(name:"presence", value:"not present")
    sendEvent(name:"lastUpdateTime", value:(new Date().toString()))
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

void logDebug(String msg) {
    if (logEnable) {
        log.debug(msg)
    }
}
