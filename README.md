# Level2OSC
This little Android app uses the microfone to calculate a sound pressure level. This can be sent to an OSC Port in the WiFi. There also is a button, that can be pressed.

## /Level2OSC/Level
This command is transferred periodically giving the current measurement of the sound pressure level in dB. (Message tag + int value)

## /Level2OSC/Button
This command indicates a click on the button. It doesn't have parameters.
