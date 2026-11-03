# SMACCM Phase 2 UAV Architecture — Derived Example Requirements

> Provenance: model-derived example, curated with AI assistance and checked against the pinned source.
> This is not an original stakeholder specification. Use it for architecture reconstruction and tool demonstrations; do not use it for independent evaluation or as a gold answer.

Model the following selected architecture in AADL. Requirements identified below are normative for this example; scope limitations are explanatory and do not request reproduction of source defects.

## Scope

UAS radio context and UAV board composition

PX4FMU/PX4IOAR/ODROID hardware, sensor/motor interfaces and memory allocation

Copter/mission software, internal communication-security paths, typed queues and dispatch periods

Selected initialization, motor-output and conditional PPM-output AGREE contracts

## Requirements

### PHASE2-REQ-001

The unmanned-aircraft system shall comprise one unmanned aerial vehicle (UAV), one ground station, and one radio-control (RC) controller, with separate 915 MHz and 2.4 GHz radio-spectrum buses.

### PHASE2-REQ-002

The ground station and UAV shall exchange bidirectional encrypted-MAVLink data over the 915 MHz radio domain. The RC controller shall send flight-control commands to the UAV through a distinct 2.4 GHz radio domain.

### PHASE2-REQ-003

The UAV shall expose a bidirectional encrypted-MAVLink data port and an input flight-control-command data port. Flight-control commands shall be represented by a structure containing eight floating-point PPM fields, ppm0 through ppm7.

### PHASE2-REQ-004

The UAV shall contain separate copter software and mission software systems together with a PX4FMU board, a PX4IOAR board, an ODROID-XU board, and an ODROID daughter board.

### PHASE2-REQ-005

The PX4FMU expansion feature group shall connect to the PX4IOAR expansion connector. The daughter-board IDE and MIPI feature groups shall connect to the corresponding ODROID-XU feature groups.

### PHASE2-REQ-006

Copter software and mission software shall each contain a flight-generation process. The copter process shall be memory-bound to PX4FMU microcontroller flash, while the mission process shall be memory-bound to ODROID-XU DRAM.

### PHASE2-REQ-007

The copter flight-generation process shall be designated for the eChronos operating system, and the mission flight-generation process shall be designated for seL4.

### PHASE2-REQ-008

The PX4FMU microcontroller subsystem shall contain an ARM Cortex-M4 processor, 128 KByte read-write SRAM based at hexadecimal 20000000, and 1 MByte execute-only flash based at hexadecimal 08000000, connected through an AHB bus matrix.

### PHASE2-REQ-009

The ODROID-XU subsystem shall contain a Cortex-A15 quad processor and 2 GByte of read-write DRAM connected by a memory bus.

### PHASE2-REQ-010

The PX4FMU shall provide a reset device, USB device, battery monitor, GPS receiver, MPU-6000 motion sensor, MS5611 barometer, HMC5883L magnetometer, EEPROM, and an I2C bus around its STM32F415 microcontroller subsystem.

### PHASE2-REQ-011

The barometer, magnetometer, and EEPROM shall attach to the PX4FMU I2C bus, which shall attach to the microcontroller I2C2 interface. Barometric and magnetometer measurements shall be exposed through the board sensor interface.

### PHASE2-REQ-012

The PX4FMU battery monitor shall connect to the microcontroller ADC interface, GPS to USART6, the MPU-6000 motion sensor to SPI1, and USB to the USB OTG interface. Reset, battery, GPS, and motion outputs shall be exposed through the board sensor interface.

### PHASE2-REQ-013

The UAV sensor feature group shall contain input data ports for magnetometer, barometric, motion, battery, and GPS information, together with a reset event-data input. PX4FMU shall supply that feature group to the copter software.

### PHASE2-REQ-014

The PX4IOAR board shall contain four motors, a buzzer, an RC radio, a 3DR radio, and a USART2 motor bus. Each motor shall accept its own servo-command data port and attach to the shared USART2 motor bus.

### PHASE2-REQ-015

The PX4IOAR buzzer shall attach to the expansion alarm interface. The RC radio shall attach to the expansion PPM interface and 2.4 GHz spectrum; the 3DR radio shall attach to expansion USART1 and the 915 MHz spectrum, with bidirectional encrypted-MAVLink interfaces on its internal and external sides.

### PHASE2-REQ-016

The daughter board shall contain MPU, gyroscope, barometer, and accelerometer devices attached to the IDE SPI1 bus, and shall route IDE UART0 to its external UART interface.

### PHASE2-REQ-017

At the UAV integration boundary, declare two separate event-data connections from mission software to copter software: one carrying RC-channel override messages and one carrying control-law requests. The sender and receiver interfaces shall each have queue capacity 16.

Qualification: Mission_Software::Software.Impl instantiates the mission logic but does not delegate its outputs to the wrapper outputs. These UAV-level connections do not establish a complete internal-mission-task-to-copter path.

### PHASE2-REQ-018

The mission process shall contain shared process data, a datalink thread, communication-security, ground-control-service, and UART thread groups, and a MAVLink-input task.

### PHASE2-REQ-019

Within the mission process, outbound ground-control bytes shall flow from the ground-control-service group to communication security, then to datalink framing, and then to UART5 output. Received datalink frames shall flow through communication security to the ground-control MAVLink stream, while datalink status shall feed the ground-control radio-status input.

### PHASE2-REQ-020

The communication-security group shall separate encryption and decryption into two threads. Its plaintext input and output event-data ports shall carry 80-byte unsigned-byte arrays with queue capacity 16; its encrypted input and output ports shall carry 96-byte unsigned-byte arrays with queue capacity 8.

### PHASE2-REQ-021

The copter process shall contain a PX4IOAR service thread, sensor-processing group, position-state and motor-state threads, motor mixer, blink/status thread, user-input group, flight-control group, UART group, and shared process data.

### PHASE2-REQ-022

Copter software shall route mission RC overrides and control-law requests to the user-input group, sensor positions to the position-state thread, flight-control outputs to the motor mixer, and motor-mixer outputs to both the PX4IOAR service and motor-state thread.

### PHASE2-REQ-023

The flight-control group shall access shared sensor data, user input, control law, control parameters, and attitude/altitude-control state. The motor mixer and blink/status thread shall receive the shared control law, and user-input proxies shall publish user-input and control-law state into shared process data.

### PHASE2-REQ-024

The copter PX4IOAR service shall expose four individual motor-command outputs to the UAV motor-and-radio feature group. It shall use hybrid dispatch with a 1 ms period, a 256-byte stack, priority value 12, and a motor-input event queue of capacity 16.

### PHASE2-REQ-025

The user-input group shall contain a user-input multiplexer, a control-law task, a PPM-input task, and separate control-law and user-input proxy threads. The multiplexer shall receive MAVLink-derived and PPM-derived user input and the selected control law; the control-law task shall receive MAVLink and PPM control-law requests.

### PHASE2-REQ-026

The PPM-input task shall use hybrid dispatch with a 50 ms period, priority value 26, and a 256-byte stack; its user-input and control-request output queues shall each have capacity 16. The control-law task shall be sporadically dispatched with priority value 24 and a 256-byte stack.

### PHASE2-REQ-027

The blink/status thread shall read the shared control law and use hybrid dispatch with a 125 ms period, priority value 20, and a 256-byte stack.

### PHASE2-REQ-028

For the copter and user-input behavioral contracts, the environment shall supply a nonnegative integer system-time input that strictly increases at every subsequent contract step.

### PHASE2-REQ-029

The user-input control-law output shall start in safe mode, encoded as 0. Control-law mode encodings shall distinguish safe (0), disarmed (1), and armed (2).

### PHASE2-REQ-030

At the user-input contract boundary, an armed-mode output (2) shall imply that an armable dead-man input and a stick-corner condition have each occurred at least once up to that step. The armable condition is PPM field ppm5 greater than 1600; the stick-corner condition is ppm2 below 1050 together with ppm3 above 1900. This history condition does not require the two events to occur at the same step.

### PHASE2-REQ-031

When the motor-mixer control law is not armed mode 2, all four motor outputs—front left, front right, back left, and back right—shall be zero.

### PHASE2-REQ-032

The motor mixer shall constrain each of its four motor outputs to the inclusive range from 0.0 to 1.0; the copter process shall retain this as a component-composition contract obligation.

### PHASE2-REQ-033

When the PPM-input component considers its retained PPM sample valid, it shall map retained fields ppm0, ppm1, ppm2, and ppm3 respectively to roll, pitch, throttle, and yaw using clamp((value - 1500.0) / 500.0, -1.0, 1.0), and timestamp the result with the current component time.

### PHASE2-REQ-034

When the PPM-input component marks its input invalid, it shall output zero roll, pitch, and yaw, throttle -1.0, and the previous output timestamp, using timestamp 0 at the initial step.

## Scope limitations

- Curated integrated-architecture requirements, not a transcription of every generated MAVLink type or implementation property.

- The project is incomplete: todo.txt requests ODROID details, CAN connectivity, PX4-to-ODROID connections, and software completion. Missing physical links are not invented.

- The RC path has Unimplemented markers and the copter-to-PPM connection is commented out. Component PPM contracts do not establish a complete end-to-end RC safety mechanism.

- Mission software does not fully wire the system wrapper or all tasks. Internal routes are specified only within evidenced boundaries.

- PPM captured is a TODO/unconstrained variable and output_ready is unconstrained. Dependent validity/arming timing behavior is not promoted into a complete operational requirement.

- Dummy true contracts, suspicious ppm_last7=ppm6, unused motor-floor/adjustment equations, UART-labelled SPI types and the Cortex-M4 60us clock are excluded.

- Integer system-time units are unspecified; no milliseconds are assigned to AGREE timeout/hysteresis constants. Scheduling Period values retain explicit ms units.

- Memory bindings are not processor bindings. No schedulability, cryptographic implementation, airworthiness, execution or proof result is asserted.

- Related project website thresholds differ and are not merged with the fixed model.

## Provenance

Source project: [smaccm/smaccm](https://github.com/smaccm/smaccm/tree/7393c423b5aca1a272a88cbb89b5dcfd08a5a47c/models/SmaccmPhase2).
Pinned source commit: `7393c423b5aca1a272a88cbb89b5dcfd08a5a47c`.

Author contracts remain part of the model. Related project website instructions have no verified commit/version match and serve only as background.

Original SMACCM BSD 3-Clause LICENSE retained.

The original mining-time commit of the A3D instance is unknown. The requirement-to-source mapping describes the pinned upstream snapshot, not a recovered historical stakeholder specification.

Line-level traceability: `example/reference/public/a3d/requirements/a3d_smaccm_phase2.json`.
