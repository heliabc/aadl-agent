# Flight management IMA/AFDX architecture - derived example requirements

> Provenance: model-derived example, curated with AI assistance and checked against the pinned source.
> This is not an original stakeholder specification. Use it for architecture reconstruction and tool demonstrations; do not use it for independent evaluation or as a gold answer.

Model the following selected architecture in AADL. Requirements identified below are normative for this example; scope limitations are explanatory and do not request reproduction of source defects.

## Scope

Covers fms::fms.impl and its four module implementations, five function threads, typed exchanges, CPU/memory bindings, partition schedules, CAN/AFDX topology, six virtual links and explicit flows.

Only the active three-switch Actual_Connection_Binding configuration is in scope. Module roots are subviews of this one example, not independent requirement cases.

## Requirements

### FMS-001

The FMS example shall contain four execution modules, one keyboard, one display, one remote data concentrator (RDC), one CAN bus, one physical AFDX network and three AFDX switch devices.

### FMS-002

Module 1 shall host separate keyboard-unit (KU1) and multifunction-display (MFD1) processes; module 2 shall host the flight-manager (FM1) process; module 3 shall host an ADIRU process; and module 4 shall host a navigation-database (NDB) process.

### FMS-003

Each execution module shall contain one processor and one main-memory component. Each hosted process shall be bound to its corresponding virtual-processor partition and dedicated memory segment; module 1 shall provide separate KU1 and MFD1 bindings.

### FMS-004

The keyboard shall emit pilot-request event data; the display shall receive display event data. Both device interfaces shall require CAN access. The RDC shall emit pressure event data and require AFDX access.

### FMS-005

The keyboard request shall enter module 1 and be delegated to KU1. KU1 shall contain one thread that accepts the request and emits a waypoint identifier through the process and module outputs.

### FMS-006

Module 1 shall send its waypoint identifier to module 2. The FM1 process and its single thread shall accept that identifier and expose a database-query output; module 2 shall send this query to module 4.

### FMS-007

Module 4 shall delegate each database-query input to the single NDB thread and expose its database-answer output; this answer shall return to the FM1 process in module 2.

### FMS-008

FM1 shall expose a database-answer-to-waypoint-information flow. The resulting waypoint information shall travel from module 2 to MFD1 in module 1 and then to the display through the MFD1 display output.

### FMS-009

The RDC pressure-data output shall feed the ADIRU process in module 3. Its single ADIRU thread shall expose a pressure-to-speed flow and send the speed output to the flight manager in module 2.

### FMS-010

The flight-manager thread shall expose a speed-to-estimated-arrival-time flow and send the resulting ETA data to the display process in module 1, whose thread shall expose an ETA-to-display flow.

### FMS-011

All functional request, waypoint identifier, waypoint information, database query, database answer, pressure, speed, ETA and display interfaces described by this example shall be typed event-data ports, with the corresponding named data classifiers retained.

### FMS-012

The KU1 thread shall be periodic with a 50 ms period, a 50 ms deadline and a declared execution-time interval of 0-25 ms.

### FMS-013

The MFD1 thread shall be periodic with a 50 ms period, a 50 ms deadline and a declared execution-time interval of 0-25 ms.

### FMS-014

The FM1 thread shall be periodic with a 60 ms period, a 60 ms deadline and a declared execution-time interval of 0-30 ms.

### FMS-015

The ADIRU thread shall be periodic with a 60 ms period, a 60 ms deadline and a declared execution-time interval of 0-30 ms.

### FMS-016

The NDB thread shall be periodic with a 100 ms period, a 100 ms deadline and a declared execution-time interval of 0-20 ms.

### FMS-017

Every virtual-processor partition shall declare highest-priority-first POSIX scheduling. This specifies the partition scheduling policy; no numeric thread priorities are added because the source priority assignments are commented out.

### FMS-018

Module 1 shall use a 50 ms ARINC653 major frame with a 25 ms KU1 partition slot followed by a 25 ms MFD1 partition slot, each marking a periodic-processing start.

### FMS-019

Module 2 and module 3 shall each use a 60 ms ARINC653 major frame. Each shall contain two 30 ms slots for its own single partition, and each slot shall mark a periodic-processing start.

### FMS-020

Module 4 shall use a 100 ms ARINC653 major frame containing a 20 ms NDB slot followed by an 80 ms NDB slot; both slots shall mark periodic-processing starts.

### FMS-021

The keyboard-to-module-1 request and module-1-to-display connections shall be bound to CAN. Both keyboard and display devices, and the corresponding module-1 CAN access features, shall be connected to that bus.

### FMS-022

The AFDX physical bus shall contain six virtual links named VL1 through VL6. All four modules, the RDC and each of the three switches shall expose accesses connected to the physical AFDX network.

### FMS-023

The waypoint-identifier connection shall be bound to VL1; waypoint-information and ETA connections shall share VL2; database query shall use VL3; database answer shall use VL4; speed shall use VL5; and pressure shall use VL6.

### FMS-024

VL1, VL2, VL3, VL4, VL5 and VL6 shall have declared bandwidth-allocation gaps of 32, 8, 16, 64, 32 and 32 ms respectively. Each shall retain the locally declared 1 ms latency-constraint property, which is a model input rather than an established traversal-time result.

### FMS-025

The virtual-link type shall declare a maximum jitter parameter of 500 us. Every switch device shall declare a switching-delay parameter of 140 us.

### FMS-026

For the active Actual_Connection_Binding route configuration, VL1 and VL4 shall follow sw1 then sw2; VL2 and VL3 shall follow sw2 then sw1; VL5 shall follow sw3, sw1, sw2; and VL6 shall use sw3.

### FMS-027

The model shall expose five named inter-module end-to-end flows: waypoint identifier, waypoint information, database query, database answer and speed. It shall also expose the complete keyboard-request chain through module 1, module 2, module 4, module 2 and module 1 to the display.

### FMS-028

The module-level flow paths shall be refined through their process and thread boundaries for request-to-waypoint, waypoint-information-to-display, waypoint-to-query, answer-to-information, speed-to-ETA, pressure-to-speed and query-to-answer processing.

## Scope limitations

- These requirements reproduce architectural declarations, not executable flight-management algorithms or proof of schedulability/network feasibility. Existing OSATE validation is not a pass.

- The top-level flow latency bounds at fms.aadl lines 72-76 are comments and are not normative limits in this draft.

- The alternative paths_specification annex refers to sw4 although sw4 is commented out; those contradictory routes are excluded rather than treated as requirements.

- The physical-bandwidth and message-size literals in fms_hardware.aadl need unit/standard review; this draft does not promote them to AFDX requirements or silently substitute paper values.

- The REAL annex invokes a restriction theorem but its local check is 1=1; its presence is not proof of ARINC653 compliance.

- The author report identifies the same model family and depicts the four modules, but no commit identifier binds its diagrams, analysis assumptions or optimized network settings to this source revision.

## Provenance

Source project: [OpenAADL/AADLib](https://github.com/OpenAADL/AADLib/tree/c8a5c7e310b37761bb5ecc7b5ff3fc6cbd42716a/examples/fms).
Pinned source commit: `c8a5c7e310b37761bb5ecc7b5ff3fc6cbd42716a`.

An author-authored design/analysis report explicitly linked to the AADLib FMS was located. It is not a standalone, revision-matched original requirement set. The draft therefore remains entirely model_derived.

Original AADLib BSD 3-Clause LICENSE and per-file notices retained.

The original mining-time commit of the A3D instance is unknown. The requirement-to-source mapping describes the pinned upstream snapshot, not a recovered historical stakeholder specification.

Line-level traceability: `example/reference/public/a3d/requirements/a3d_fms.json`.
