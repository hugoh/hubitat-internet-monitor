# Hubitat Internet Connection Monitor

This is an Hubitat application to report on whether the Internet connection is
up or down.

The application exposes a child presence sensor:
* When the internet is up, the presence sensor is set to present.
* When the internet is detected as down, it is set as not present.

## Logic

### Checks

A series of HTTP requests are attempted, and then a series of pings. As soon as
one check is successful, the connection is detected as up.

Optionally, a latency error threshold can be specified in the configuration.
When the response time for an HTTP request or an ICMP ping exceed the error
threshold, the check is considered as failed. This allows to detect situations
where the connection is still up but very deteriorated.

The list of URLs and hosts are configurable and rotated after each test.

### Check loop

When the connection is up, tests are run every 5 minutes (configurable). When
it's down, they're run every minute (also configurable).

## Credits

The original code was initially based on [achaudhari's ping presence
sensor](https://github.com/achaudhari/hubitat-drivers/tree/cee6fc7b9682da862ff7b497ed096e0014d4c8f7/ping-presence-sensor).
