Cowork post-merge review of main (PR A): #377 stopped recognising some question forms the guard recognised before. Two causes, both in the patterns #377 widened: (1) the new "can/could/may i ask" openers are stripped before the lead-phrase test, so "can I ask you to turn on the heater?" is no longer a question; (2) the list-marker pattern accepted 1 to 4 letters followed by a dash or bracket, so a short question word such as "is - it on" was eaten as a marker.

Fix: remove the three ask openers (the lead phrase "can i" already recognises them); the marker is now a digit, one letter or a roman numeral.

Tests that fail on current main: QuestionShapeNoRegressionTest builds 35 prefixes x 50 cores (lead words, lead phrases, marker-like forms, ask-style capability questions) and requires that everything QuestionShapePre377 (the guard exactly as before #377, kept as an oracle) recognised is still recognised. On main it fails 3 of 3 tests, with 4 distinct lost cores (the can/could i ask forms and "is - it on") across all prefixes. The guard only ever withholds a proposal, so recognising more is harmless; recognising less was the regression. Impact today is limited (administrator-only, flag off, no executor).

Verified: guard, proposal and controller classes pass; full backend suite 976 run, 0 failures, 39 errors that all need Docker (baseline). Backend deploy path: needs an independent CLEAN on the exact head, then the deploy go.

🤖 Generated with [Claude Code](https://claude.ai/code)
