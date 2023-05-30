# Hubitat Internet Connection Monitor

This is a Hubitat driver to report on whether the Internet connection is up or down.

This is done via a presence sensor, whose state reflects whether a set of HTTP GET and ICMP echo requests are successful.

When the internet is up, the presence sensor is set to present. When the internet is detected as down, it is set as not present.

Note that HTTP checks run before ping checks, and the tests are run more often when the internet has been detected as down.

The code was initially based on [achaudhari's ping presence sensor](https://github.com/achaudhari/hubitat-drivers/tree/cee6fc7b9682da862ff7b497ed096e0014d4c8f7/ping-presence-sensor), but there isn't much of it left at this point. Thank you for the inspiration though.
