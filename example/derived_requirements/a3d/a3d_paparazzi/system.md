# Paparazzi airborne architecture - derived example requirements

> Provenance: model-derived example, curated with AI assistance and checked against the pinned source.
> This is not an original stakeholder specification. Use it for architecture reconstruction and tool demonstrations; do not use it for independent evaluation or as a gold answer.

Model the following selected architecture in AADL. Requirements identified below are normative for this example; scope limitations are explanatory and do not request reproduction of source defects.

## Scope

Covers the common airborne hierarchy, MCU0/MCU1 process and hardware boundaries, task sets, modeled connections and all four scheduling variants.

The four configurations are alternatives within one case, not four independent requirement datasets.

## Requirements

### PAP-001

The example shall model one airborne system containing two separate subsystems: an autopilot (MCU0) and a fly-by-wire controller (MCU1), connected to one SPI bus. The ground station is outside the modeled system boundary.

### PAP-002

The airborne boundary shall accept radio-control event-data input and forward it to MCU1; MCU1 shall expose an outgoing inter-MCU message channel to MCU0, and MCU0 shall expose a return event-data channel to MCU1.

### PAP-003

MCU0 shall contain one processor, one RAM, a memory bus, a device bus, a modem bus and one navigation/stabilization/control process. That process shall be bound to the MCU0 processor and RAM.

### PAP-004

MCU0 shall include pressure, voltage, compass, air-speed, camera, transmitter, GPS and infrared devices. The pressure, voltage, compass, air-speed and GPS data ports shall feed corresponding process-boundary inputs, and the GPS error event shall feed the process GPS-event input.

### PAP-005

MCU0 shall connect its process telemetry output to the transmitter data input and the camera video output to the transmitter video input. The inter-MCU input and output shall be delegated through the navigation/stabilization/control process boundary.

### PAP-006

MCU1 shall contain one processor, one RAM, memory and device buses, one radio receiver, one servo counter and one radio-command/servo-control process. Its process shall be bound to its processor and RAM.

### PAP-007

MCU1 shall provide six distinct servo devices and command channels: elevator, rudder, left and right ailerons, and left and right motors. Each process command output shall pass through the corresponding counter channel to its servo.

### PAP-008

The MCU1 radio receiver shall receive the external radio-control input and forward its command data and event indication to the servo-control process. That process shall receive autopilot commands and expose messages for MCU0.

### PAP-009

The autopilot process shall contain eight periodic task instances: radio control, stabilization, inter-MCU sending, GPS data acquisition, navigation, altitude control, climb control and ground reporting.

### PAP-010

The radio-control and stabilization tasks shall each have a 100 ms period; their execution-time intervals shall be 15,600-21,100 us and 5,681-6,654 us respectively.

### PAP-011

The inter-MCU sending task and GPS acquisition task shall each have a 250 ms period; their execution-time intervals shall be 233-471 us and 5,987-6,659 us respectively.

### PAP-012

Navigation, altitude control and climb control shall each have a 250 ms period; their execution-time intervals shall be 44,420-53,350 us, 1,478-1,660 us and 5,429-6,241 us respectively.

### PAP-013

The ground-reporting task shall have a 100 ms period and an execution-time interval of 5,000-12,220 us.

### PAP-014

The autopilot process shall additionally contain four interrupt-handler thread instances: two SPI handlers, one modem handler and one GPS handler. Their execution-time intervals shall be 251-447 us, 151-228 us, 303-520 us and 283-493 us respectively.

### PAP-015

GPS acquisition shall provide position event data to navigation. In auto and home modes, navigation shall provide desired altitude to altitude control, which shall provide desired climb to climb control; the two control values shall use Float_32 event-data ports.

### PAP-016

The autopilot process infrared data input shall feed the stabilization task. The GPS stream input shall feed the GPS interrupt handler.

### PAP-017

In manual, auto and home modes, the autopilot sending task shall feed the first SPI handler, then the second SPI handler, then the process inter-MCU output. Reception from MCU1 through the two SPI handlers shall be enabled only in manual mode.

### PAP-018

Ground reporting shall pass through the modem handler to the process ground-transmission output in manual, auto and home modes.

### PAP-019

The autopilot process shall declare manual as its initial mode and shall also declare auto and home modes. These mode declarations shall define connection availability; no autonomous transition condition is specified by this example.

### PAP-020

The MCU1 process shall contain five periodic tasks: PPM/radio-command checking and sending to the autopilot at 25 ms periods; servo transmission, autopilot-value checking and failsafe checking at 50 ms periods.

### PAP-021

The MCU1 process shall additionally contain eight interrupt-handler thread instances: one for each of the six servo channels, one SPI handler and one radio handler.

### PAP-022

The MCU1 process shall retain four Boolean state-data components: system mode, radio available, radio really lost and autopilot health. This requirement specifies stored architectural data only; it does not define an update algorithm.

### PAP-023

In manual mode, the MCU1 process shall pass incoming radio-control event data to its radio handler, and its sending task shall forward an outgoing message through the SPI handler to MCU0. Autopilot command reception through the SPI handler shall be present in manual and auto modes.

### PAP-024

Each of the six MCU1 servo interrupt handlers shall drive its matching signed 16-bit process command output.

### PAP-025

The MCU1 process shall declare manual as its initial mode, plus auto and failsafe modes. Transition triggers and failsafe control actions are outside these source declarations.

### PAP-026

The basic_archi configuration shall retain the common airborne architecture and periodic task properties without adding a system-level interrupt-dispatch or scheduler override.

Applies to: `paparazzi_system::paparazzi.basic_archi`.

### PAP-027

The PnP_tasks_interruptions configuration shall extend basic_archi and configure both processors for rate-monotonic scheduling with preemption disabled.

Applies to: `paparazzi_system::paparazzi.PnP_tasks_interruptions`.

### PAP-028

In PnP_tasks_interruptions, autopilot SPI handlers shall be periodic at 50 ms, modem at 100 ms, and GPS at 250 ms. MCU1 elevator, left motor, right motor, left aileron, right aileron and rudder handlers shall have periods of 50, 52, 54, 56, 58 and 60 ms respectively; its SPI and radio handlers shall each have a 25 ms period. These are periods, not offsets.

Applies to: `paparazzi_system::paparazzi.PnP_tasks_interruptions and inherited PP_tasks_interruptions`.

### PAP-029

The PP_tasks_interruptions configuration shall extend PnP_tasks_interruptions, retain its periodic interrupt properties and rate-monotonic scheduler, and enable preemption on both processors.

Applies to: `paparazzi_system::paparazzi.PP_tasks_interruptions`.

### PAP-030

The PP_tasks_AP_interruptions configuration shall extend basic_archi, use rate-monotonic preemptive scheduling on both processors, and make all four autopilot interrupt handlers and all eight MCU1 interrupt handlers aperiodic; it shall retain the common periodic ordinary-task declarations.

Applies to: `paparazzi_system::paparazzi.PP_tasks_AP_interruptions`.

## Scope limitations

- These are source-derived architectural requirements, not executable control laws or a claim of validation. Existing OSATE validation is not a pass.

- The source declares modes without transitions and leaves several task inputs/behavior bodies unimplemented; no transition guards, servo algorithms, sensor processing or failsafe actions are invented.

- The imported source references historical C files; those references are not proof that matching C bodies are present.

- Paper table 2 gives radio-control/stabilization/inter-MCU-send frequencies of 40/20/20 Hz, while this fixed model uses periods of 100/100/250 ms. This draft follows the model snapshot and does not merge the paper timing table.

- The original AADLib source has historical linking/type issues and a GPL provenance comment despite its repository-level BSD-3-Clause license; this draft does not relicense the imported source.

## Provenance

Source project: [OpenAADL/AADLib](https://github.com/OpenAADL/AADLib/tree/c8a5c7e310b37761bb5ecc7b5ff3fc6cbd42716a/examples/paparazzi).
Pinned source commit: `c8a5c7e310b37761bb5ecc7b5ff3fc6cbd42716a`.

A publisher-hosted PapaBench paper and explicit PapaBench v0.4 provenance were located. No standalone original requirement set matching all four revised AADLib configurations was established.

AADLib root LICENSE retained together with the model's GNU-GPL/PapaBench origin notice; no blanket BSD relabeling.

The original mining-time commit of the A3D instance is unknown. The requirement-to-source mapping describes the pinned upstream snapshot, not a recovered historical stakeholder specification.

Line-level traceability: `example/reference/public/a3d/requirements/a3d_paparazzi.json`.
