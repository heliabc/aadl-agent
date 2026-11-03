# AOCS subsystem interfaces and deployment - derived example requirements

> Provenance: model-derived example, curated with AI assistance and checked against the pinned source.
> This is not an original stakeholder specification. Use it for architecture reconstruction and tool demonstrations; do not use it for independent evaluation or as a gold answer.

Model the following selected architecture in AADL. Requirements identified below are normative for this example; scope limitations are explanatory and do not request reproduction of source defects.

## Scope

Covers the instantiated root, eight functional thread declarations, two modes, device interfaces, explicit internal functional connections, process-boundary wiring and processor/bus bindings.

This is a structural design example with incomplete internal delegation, not a closed-loop satellite controller.

## Requirements

### AOCS-001

The AOCS subsystem shall contain one AOCS processing process, one LEON2-derived processor, eight sensor devices, three actuator devices, a satellite central-computer device, a watchdog device, an internal DMA bus and a satellite OBDH bus.

### AOCS-002

The AOCS processing process shall be bound to the dedicated LEON2-derived processor; the processor shall declare a round-robin scheduling protocol.

### AOCS-003

The eight sensor devices shall be FSS, CSS, SPS, ES, MGM, GYR, STR and GPS. Each shall expose an outgoing typed measurement data port, an incoming power-status data port, an incoming sensor-reconfiguration data port and a required DMA-bus access.

### AOCS-004

The three actuator devices shall be THU, RW and MGT. Each shall expose an incoming typed control-data port, an incoming power-status data port, an incoming actuator-reconfiguration data port and a required DMA-bus access.

### AOCS-005

The satellite central-computer device shall provide telecommands to the AOCS process and receive telemetry from it; these two process-boundary connections shall be bound to the OBDH bus.

### AOCS-006

The process shall contain eight functional thread instances: attitude control (ACF), orbit control (OCF), telecommand processing (TCP), telemetry processing (TMP), failure detection/isolation (FDR), failure recovery (FR), reconfiguration (Reconfig), and manoeuvre execution (ME). The root shall reference their thread types, without selecting the separately declared .impl classifiers.

### AOCS-007

The process shall declare normal mode as initial and safe mode as the second mode. Forcing_Safe_Mode shall change normal to safe, and Getting_Back_To_Normal_Mode shall change safe to normal.

### AOCS-008

Orbit control, failure detection/isolation, failure recovery and manoeuvre execution shall be active only in normal mode. Attitude control, telecommand processing, telemetry processing and reconfiguration shall have no mode restriction at their subcomponent declarations.

### AOCS-009

The attitude-control thread interface shall accept nominal attitude, sensed gyroscope attitude and fine-sun-sensor direction, and shall expose a reaction-wheel torque-reference output.

### AOCS-010

The orbit-control thread interface shall accept a nominal orbit and expose a thruster-force reference output.

### AOCS-011

Within the process, telecommand processing shall supply nominal attitude to attitude control and nominal orbit to orbit control.

### AOCS-012

Within the process, telecommand processing shall send manoeuvre commands to manoeuvre execution and shall supply the attitude window and checking thresholds to failure detection/isolation.

### AOCS-013

Within the process, failure recovery shall supply reconfiguration commands to the reconfiguration thread.

### AOCS-014

At the process boundary, GYR measurement data shall feed the sensed-attitude input and FSS measurement data shall feed the sun-direction input. The process torque-reference output shall connect to RW, and its force-reference output shall connect to THU.

### AOCS-015

At the process boundary, eleven distinct power-status outputs shall connect to the matching power-status inputs of the eight sensors and the THU, RW and MGT actuators.

### AOCS-016

At the process boundary, eleven reconfiguration outputs shall connect to the matching eight sensor-reconfiguration and three actuator-reconfiguration inputs.

### AOCS-017

At the process boundary, a manoeuvre torque-command output shall connect to RW and a manoeuvre force-command output shall connect to THU.

### AOCS-018

The watchdog device shall accept a watchdog event from the AOCS process and return an anomaly-detected event to the process.

### AOCS-019

The process outputs Force_mode_to_SM and Force_mode_to_NM shall be connected back to its safe-mode and normal-mode transition inputs respectively, providing the declared manual mode-change paths.

### AOCS-020

The process outputs Changing_mode_to_SM and Changing_mode_to_NM shall be connected back to its safe-mode and normal-mode transition inputs respectively, providing the declared recovery mode-change paths.

### AOCS-021

The 28 explicitly named DMA connections shall be bound to the DMA bus: four attitude/orbit paths, eleven power-status paths, eleven reconfiguration paths and two manoeuvre-output paths.

### AOCS-022

The failure-detection/isolation thread interface shall expose inputs for gyroscope attitude, fine-sun direction, attitude window, eight typed sensor measurements and check thresholds, together with a watchdog-event output and an anomaly-detected input. This specifies the interface only; no detection algorithm is defined.

### AOCS-023

The reconfiguration thread interface shall accept one reconfiguration command and expose eight sensor-reconfiguration outputs and three actuator-reconfiguration outputs; the manoeuvre-execution interface shall accept a manoeuvre command and expose reaction-wheel torque and thruster-force outputs.

### AOCS-024

The telemetry thread shall expose eight typed sensor-measurement inputs and an outgoing telemetry data port. The example does not require a sensor-to-telemetry route through the process boundary because that routing is not completed in this source.

## Scope limitations

- The root instantiates thread types, not software_aocs thread implementations; periods, deadlines and execution times of the unselected .impl classifiers are deliberately not imposed.

- The model includes only six connections inside the process. Boundary sensor/control/telemetry connections do not establish complete routes to every internal thread. The telemetry sensor-input TODO is not converted into a new requirement.

- The telemetry force_command direction and the telecommand power_status_TRU spelling are inconsistent with neighboring declarations; neither is imposed as a normative interface requirement.

- No control laws, failure thresholds, recovery guards, real-time feasibility or mission-safety guarantees are inferred. Existing OSATE validation is not a pass.

- The upstream AOCS prototype describes CSP/FSP modes and excludes orbit control, whereas this AADLib root has NM/SM and an OCF thread. Its numerical prototype requirements therefore are not imported.

## Provenance

Source project: [OpenAADL/AADLib](https://github.com/OpenAADL/AADLib/tree/c8a5c7e310b37761bb5ecc7b5ff3fc6cbd42716a/examples/aocs).
Pinned source commit: `c8a5c7e310b37761bb5ecc7b5ff3fc6cbd42716a`.

The AADLib README names the historical ETH AOCS Framework design-document website. The author-maintained project site and its document archive were read. They are domain/framework design material; a version-pinned original requirement set for this exact AADLib root has not been established.

Original AADLib BSD 3-Clause LICENSE and per-file notices retained.

The original mining-time commit of the A3D instance is unknown. The requirement-to-source mapping describes the pinned upstream snapshot, not a recovered historical stakeholder specification.

Line-level traceability: `example/reference/public/a3d/requirements/a3d_aocs.json`.
