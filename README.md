###Trip Tracker

This is an Android app I threw together in a few hours to do periodic GPS
location tracking on a phone and send the GPS coordinates to an HTTP server.

Enter a URL that accepts POST requests, choose a poll interval, and tap "Enable
Tracking".  The Android location manager will return a GPS location every so
often around that interval (it is not exact) and the information will be POSTed
to your URL.

The POSTed parameters are a `locations` array, each element being a hash
including `time` (a Unix Timestamp from the Location service), `latitude` and
`longitude` (two float values of arbitrary precision), and `speed` in meters
per second.

There is some error handling for when you travel out of reach of data service
or the server is not responding properly.  Location points are stored in a
queue and retried until they are received by the server with a 200 HTTP status.

####Disclaimer

I wrote this to track my location on a long trip to display the coordinates on
a map.  This application does use a background service, but a persistent
notification/icon is displayed.  Please don't disable that in order to use this
application for creepy stalking (there are probably much more stealthy apps
available in the Market to do that, ok?)

####Building

- `ant debug install` should do it

Compiled versions may be available at
[https://github.com/jcs/triptracker/downloads](https://github.com/jcs/triptracker/downloads).

####Screenshot

![screenshot](https://raw.github.com/jcs/triptracker/master/screenshot.png)
