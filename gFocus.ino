// Slow PWM on pin 3 for multimeter testing
// Duty cycle adjustable in code

const int pwmPin = 3;   // PWM output pin
const int period = 1; // period in ms (1 second total cycle)
int dutyCycle = 50;     // percentage (0-100)

void setup() {
  pinMode(pwmPin, OUTPUT);
}

void loop() {
  // HIGH for dutyCycle%
  digitalWrite(pwmPin, HIGH);
  delay(period * dutyCycle / 100);

  // LOW for the rest
  digitalWrite(pwmPin, LOW);
  delay(period * (100 - dutyCycle) / 100);
}
