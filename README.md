# Regeneration Meter Enhanced
The regeneration meter, kept in step with the game. Nothing tells the client where the server is in a cycle, so the meter counts ticks on its own and drifts. This one puts the ring back to the start every time a hitpoint or a tenth of special attack energy actually lands, so it resyncs itself every cycle.

Special attack energy stops being restored between Fortis Colosseum waves and between Doom of Mokhaiotl floors. Neither pause holds the cycle where it stood, it starts it over, so the first energy after a wave or a floor is a whole cycle away. Closing the Colosseum interface between waves does not start it coming again.

Rapid Heal and the hitpoints cape each halve the hitpoints cycle and do not stack with each other. The regen bracelet doubles the hitpoints a cycle pays rather than the rate, so it stacks with either for four a minute. Anything else that changes the cycle is picked up from your own regens.

Turn the built-in Regeneration Meter off or both rings are drawn.

## Settings
**Show hitpoints regen**: ring around the hitpoints orb.

**Show spec. attack regen**: ring around the special attack orb.

**Show at full hitpoints**: keeps the hitpoints ring up at full hitpoints, where there is nothing to regen.

**Dim while waiting**: dims a full ring while its regen is due but has not come.

**Hitpoint notification**: notifies this many seconds before your next hitpoint. 0 is off.
