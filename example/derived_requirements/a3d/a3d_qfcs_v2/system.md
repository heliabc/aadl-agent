# QFCS V2 derived architectural example requirements

> Provenance: model-derived example, curated with AI assistance and checked against the pinned source.
> This is not an original stakeholder specification. Use it for architecture reconstruction and tool demonstrations; do not use it for independent evaluation or as a gold answer.

Model the following selected architecture in AADL. Requirements identified below are normative for this example; scope limitations are explanatory and do not request reproduction of source defects.

## Scope

Four-FCC architecture and nested ISAS/OSAS/CLAW structure

Redundant typed inputs, 21-field actuator interface and local data paths

Embedded author requirements for input health checks, fault latching and output monitoring

Behavioral abstraction and bounds; no concrete processor, bus, memory or scheduling deployment is supplied upstream

## Requirements

### QFCS-DR-001

Represent the flight-control architecture as one FCS system containing four peer instances, FCC1, FCC2, FCC3 and FCC4, of the same FCC implementation. The reference root is FCS::FCS.impl.

### QFCS-DR-002

Decompose each FCC into exactly three system subcomponents: an input-signal analysis and selection component ISAS, an output-signal analysis and selection component OSAS, and an abstract control-law component CLAW.

### QFCS-DR-003

Expose nine sensor-input groups at the FCS and FCC interfaces: quad-redundant elevators, ailerons, rudders, flaps, spoilers, stabilizers and EGI; dual-redundant angle of attack and air data. Use typed AADL data ports and retain the distinction between each redundant group and its selected single-channel value.

### QFCS-DR-004

Represent each elevator sample with ELLOB, ELLIB, ELROB and ELRIB; each aileron sample with AILL and AILR; each rudder sample with RUDU and RUDL; each flap sample with FLLOB, FLLIB, FLROB and FLRIB; each spoiler sample with SPLOB, SPLIB, SPROB and SPRIB; and each stabilizer sample with VAL. Values are floating-point, and every sample also carries an integer TIME_STAMP.

### QFCS-DR-005

Represent each angle-of-attack sample by floating-point AOA plus an integer timestamp, and each air-data sample by floating-point TAT, TotPres, Pres and Temp plus an integer timestamp; provide two samples in each redundant group.

### QFCS-DR-006

Retain the EGI abstraction as four records carrying PHI, THETA, PSI, PB, QB, RB, NX, NY, NZ, VN, VE, HDOT, LAT, LON, ALT, GAMLNS, GS, VGOT, XTACC, FPACCEL and HDD as floating-point values with an integer timestamp. Its AOA, TAT, TotPres, Pres and Temp fallback fields are provisional model abstractions, not confirmed aircraft-interface fields.

Qualification: The source explicitly marks the five fallback fields TBD; this requirement preserves that qualification.

### QFCS-DR-007

Provide four actuator-return input bundles and four actuator-command output bundles at FCS. Route return bundle i to FCC i and route FCC i's command bundle to output i, for i = 1..4. Retain eight Boolean SOV output bundles at the FCS interface, two bundles per named channel; the disputed upstream SOV routing is not prescribed here.

Qualification: All eight upstream SOV connections currently originate at FCC1; that wiring is recorded as a limitation, not adopted as a desired channel assignment.

### QFCS-DR-008

Use a common 21-field actuator schema: THROTL, THROTR, ELLOB, ELLIB, ELROB, ELRIB, AILL, AILR, RUDUP, RUDLO, SPLLIB, SPLLOB, SPLRIB, SPLROB, FLAPLI, FLAPLO, FLAPRI, FLAPRO, GEAR, BRAKE and STEER. Command/return values are floating-point; corresponding validity counts are integer; corresponding fault and SOV records are Boolean.

### QFCS-DR-009

Within each FCC, route the nine sensor groups to ISAS and expose ISAS selected values and fault flags through the corresponding FCC outputs. Route the FCC actuator return and validity-count inputs to OSAS, and route CLAW actuator commands to OSAS command inputs.

Qualification: This states local FCC wiring only; FCS-level EGI distribution and ISAS-to-CLAW functional coupling are incomplete in the imported model.

### QFCS-DR-010

Expose OSAS output commands, actuator-failure flags and two SOV bundles through the FCC. Keep OSAS inputs for actuator returns, ISAS/CLAW failure indications, CCDL failure counts, a CCDL frame counter, CCDL failure status, validity counts and OSAS failure status distinct from command values.

### QFCS-DR-011

Represent cross-channel validity accounting explicitly in the behavioral abstraction: use a shared per-actuator validity-count record, constrain all four FCC validity-count inputs to that record, and relate the counts to the four FCC actuator-failure records. This is an AGREE abstraction of CCDL behavior, not a physical communication-bus deployment.

### QFCS-DR-012

For each of the 21 actuator fields, specify an FCS contract requiring the arithmetic mean of the four FCC command values to remain in the example's inclusive range 0.0 to 50.0. These dimensionless example bounds do not specify aircraft engineering units.

### QFCS-DR-013

Specify an abstract CLAW component with one actuator-command output bundle and a contract bounding each of its 21 command values between 0.0 and 50.0 inclusive. Do not infer a control-law algorithm or selected-sensor input interface from this abstraction.

### QFCS-DR-014

Check whether dual and quad sensor inputs have updated since the previous sample frame. Maintain this stale-data assessment separately from range and inter-channel comparisons.

Qualification: Paraphrase of author-written source comments; the single-channel sentence is not generalized into an implemented single-channel ISAS path.

### QFCS-DR-015

For the eight checked sensor groups (elevators, ailerons, rudders, flaps, spoilers, stabilizers, angle of attack and air data), assess dual and quad digital input values against their operational ranges, separately from stale-data status. In this illustrative model, the configured lower and upper sensor bounds are 0.0 and 50.0; these are modeling parameters, not measured aircraft limits.

Qualification: EGI is a separate fallback source, not a ninth input passed to these stale/range/peer-comparison checks. Declared EGI constants do not establish EGI self-health checking.

### QFCS-DR-016

For the eight checked sensor groups (elevators, ailerons, rudders, flaps, spoilers, stabilizers, angle of attack and air data), compare the two channels of each dual input and compare each quad channel with peer channels using signal-specific tolerances. The example configures tolerance magnitudes as 0.1 times the corresponding upper range bound, yielding 5.0 for bounds of 50.0; EGI-specific tolerances retain their provisional status.

Qualification: EGI is a separate fallback source, not a ninth input passed to these stale/range/peer-comparison checks. Declared EGI constants do not establish EGI self-health checking.

### QFCS-DR-017

Select a healthy dual input by averaging its two signal values, subject to the component's health and tolerance checks.

### QFCS-DR-018

When one channel of a dual input is stale or outside its range, compare the remaining channel with the selected equivalent EGI parameter using the applicable EGI-comparison tolerance.

### QFCS-DR-019

When exactly one channel of a dual input is declared faulty, specify the selected value as the average of the remaining channel and the corresponding selected EGI value. This is the recorded source requirement, conditional on an available meaningful EGI fallback.

Qualification: Upstream EGI availability and validity assumptions are incomplete; no end-to-end satisfaction claim is made.

### QFCS-DR-020

When both channels of a dual input are faulty and the corresponding EGI data is valid, use the selected equivalent EGI value as the dual-input selection.

Qualification: This is the author's natural-language condition; it is not a claim that the current AGREE helper encodes every stated precondition.

### QFCS-DR-021

Latch a sensor signal fault after it persists for five consecutive frames, and retain the failed indication until FCC reset, as stated by the embedded ISAS-S-240 requirement.

Qualification: The reset is part of the author's prose; no concrete reset port or reset implementation is invented.

### QFCS-DR-022

For each actuator, compare the returned command with the previous-frame command using a 2.5 percent tolerance, and increment that actuator's fault counter when the comparison fails.

Qualification: Counter-reset behavior is deliberately unspecified: the author comment says clear if the fault clears within five frames, whereas the helper requires at least four consecutive fault-free frames. The two descriptions are not silently reconciled.

### QFCS-DR-023

Latch an actuator-command failure after five consecutive faulty frames. The failure remains latched in the corresponding OSAS failure contract; a command that is latched failed is required to be zero.

### QFCS-DR-024

After initialization, detect a local CCDL failure when its frame counter does not advance by exactly one from the preceding frame.

### QFCS-DR-026

Represent OSAS gain application for the explicitly enumerated gains: output equals input for gain 1.0, twice input for 2.0, four-thirds input for 4.0/3.0, and four times input for 4.0; for other gain values the recorded helper requires output zero. Selection of a gain remains subject to unresolved contract interactions.

### QFCS-DR-027

Preserve the two SOV output records and their separate failure inputs at OSAS so shutdown behavior can be represented without collapsing it into actuator magnitude. Do not treat the current all-FCC1 top-level SOV routing or the unverified SOV contract as an independently validated safety mechanism.

### QFCS-DR-028

At FCS level, distribute the eight explicitly connected sensor groups to all four FCCs: elevators, ailerons, rudders, spoilers, flaps, angle of attack, air data and stabilizers. Preserve this fan-out without inventing a deployed sensor bus. EGI distribution is an unresolved source gap, not an instruction to leave a production sensor path disconnected.

## Scope limitations

- OSATE core and instance checks do not validate AGREE semantics. The local CLI has no AGREE parser; no contract was proved in this task.

- The 2015 paper describes the same FCS/FCC/ISAS/OSAS/CLAW study, but no paper-to-commit identity or exhaustive requirement correspondence is established.

- FCS.aadl:101-108 routes all eight SOV outputs from FCC1. FCS.aadl:54-94 contains no top-level EGI-to-FCC connections. These are recorded as unresolved source issues and are not adopted as intended redundancy requirements.

- CLAW has no selected-sensor inputs, its implementation is empty, and several OSAS failure/status inputs are not supplied by local FCC connections. The model is a contract abstraction, not a complete flight-control implementation.

- OSAS-S-180 and OSAS-S-250 are marked THIS UNREAL in OSAS.aadl:71,80. The nominal gain helper maps valid counts 0,1,2,3 to 4,2,4/3,1, whereas FCS validity accounting can produce 0..4; these semantics and failure-rule priority require review and are not prescribed as a corrected gain-selection requirement.

- ISAS-S-220 (mismatched dual fallback) and ISAS-S-260 (quad selection table) are excluded from normative draft requirements: the paper explicitly discusses contradictions and missing equality cases. No correction is guessed.

- EGI fallback fields and comparison parameters are marked TBD in the source. Dimensionless 0..50 ranges and 5.0 tolerances are example parameters, not certified aircraft limits.

- Requirements restated from source prose may not exactly match their AGREE formalization. In particular EGI validity and FCC-reset assumptions are not supplied as implemented interfaces.

- No concrete hardware allocation, physical CCDL network, scheduling period, engineering-unit dictionary, control-law algorithm or avionics certification claim is reconstructed.

- The local-CCDL-failure gain=1 rule is kept only as an excluded source observation because it can conflict with zero output for a latched actuator failure. No priority is invented.

- OSAS_S_110 counter reset is unresolved: source prose says a fault clearing within five frames resets it, while the helper checks four consecutive fault-free frames. This draft does not prescribe either reset condition.

## Provenance

Source project: [smaccm/smaccm](https://github.com/smaccm/smaccm/tree/7393c423b5aca1a272a88cbb89b5dcfd08a5a47c/models/QFCS_V2).
Pinned source commit: `7393c423b5aca1a272a88cbb89b5dcfd08a5a47c`.

Partial author-written natural-language requirements exist as source comments and selected examples occur in the authors' 2015 paper. No complete independent requirements baseline linked to this exact commit has been located. This curated architecture-wide set is derived and cannot be an independent evaluation oracle.

Original SMACCM BSD 3-Clause LICENSE retained.

The original mining-time commit of the A3D instance is unknown. The requirement-to-source mapping describes the pinned upstream snapshot, not a recovered historical stakeholder specification.

Line-level traceability: `example/reference/public/a3d/requirements/a3d_qfcs_v2.json`.
