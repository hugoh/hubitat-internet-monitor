# Hubitat Internet Connection Monitor

This is a Hubitat driver to report on whether the Internet connection is up or down.

This is done via a presence sensor, whose state is set by a random set of HTTP GET and ICMP echo requests to a configured set of URLs and hosts.

The code was initially based on [achaudhari's ping presence sensor](https://github.com/achaudhari/hubitat-drivers/tree/cee6fc7b9682da862ff7b497ed096e0014d4c8f7/ping-presence-sensor).
