# ADIRU derived architectural example requirements

> Provenance: model-derived example, curated with AI assistance and checked against the pinned source.
> This is not an original stakeholder specification. Use it for architecture reconstruction and tool demonstrations; do not use it for independent evaluation or as a gold answer.

Model the following selected architecture in AADL. Requirements identified below are normative for this example; scope limitations are explanatory and do not request reproduction of source defects.

## Scope

Four-partition base architecture with six sensor channels and all active thread decompositions

Measurement, alarm and mitigation interfaces; processor and memory bindings

Periodic execution structure and C subprogram bindings

Separate DeOS, VxWorks653 and EMV2 variants

## Requirements

### ADIRU-DR-001

Create a software-oriented ADIRU architecture with four processes: accelerometer simulation acc_pr, accelerometer health monitoring acc_hm_pr, ADIRU computation ADIRUp_pr, and system health monitoring SHM_pr. The base root is ADIRU::ADIRU.impl.

### ADIRU-DR-002

Model one PowerPC processor containing four virtual-processor partitions named acc, acc_hm, adirup and shm. Bind the four processes to part1, part2, part3 and part4 respectively, maintaining separate execution partitions.

### ADIRU-DR-003

Model a main-memory component with four separate memory subcomponents accMem, acc_hmMem, ADIRUpMem and SHMMem, and bind each process to its corresponding memory subcomponent. Concrete memory addresses and feasible capacities remain unresolved configuration parameters.

### ADIRU-DR-004

Use distinct data types accData, errorData and actionData for measurements, error reports and corrective commands. In this reference example each derives from an unsigned integer representation with modeled size four bytes and C type mapping unsigned long int; target ABI compatibility must be checked before code generation.

### ADIRU-DR-005

Decompose acc_pr into six accelerometer-output threads, acc1 through acc6. Give each thread one accelerometer data output and one incoming event-data corrective-command port, preserving channel identity.

### ADIRU-DR-006

Connect each of the six acc_pr measurement outputs to the same-numbered acc_hm_pr measurement input using data-port connections.

### ADIRU-DR-007

Connect the six corrective event-data outputs of acc_hm_pr back to the same-numbered command inputs of acc_pr; retain six distinct feedback paths.

### ADIRU-DR-008

Implement acc_hm_pr with one health-monitor thread accepting six measurement values, producing six validated measurement values, issuing six per-accelerometer action events, receiving a system-level action, and emitting an error event.

### ADIRU-DR-009

Connect each of the six validated outputs from acc_hm_pr to the corresponding input of ADIRUp_pr, keeping health-monitoring mediation between the sensor-emulation and computation processes.

### ADIRU-DR-010

Implement ADIRUp_pr with one ADIRUp_HM thread and one ADIRUp_solve thread. Route its six incoming measurements through ADIRUp_HM and then through six distinct data connections into the solver. Do not add the commented-out second solver.

### ADIRU-DR-011

Provide one error event from acc_hm_pr and one error event from ADIRUp_pr to separate inputs of SHM_pr. Provide a corrective-action event path from SHM_pr to each of those two originating processes.

### ADIRU-DR-012

Decompose SHM_pr into an alarm aggregator, a diagnosis engine and a mitigation actor. Route both incoming error-event streams into the aggregator, then pass aggregated error data to the diagnosis engine and diagnosis results to the mitigation actor.

### ADIRU-DR-013

Broadcast the mitigation actor's action output to the two system-level corrective-action ports: one targeting accelerometer health monitoring and one targeting ADIRU computation health monitoring.

### ADIRU-DR-014

Model measurement transport as data ports and error/action transport as event-data ports across the four process boundaries. Preserve this distinction when mapping the example to ARINC653 sampling versus queuing communication services.

Qualification: The port categories are source facts; the author SEI article corroborates the ARINC653 sampling/queuing mapping. No specific queue capacity or overflow policy is supplied.

### ADIRU-DR-015

Use periodic dispatch for all twelve active threads. The six accelerometer-output threads, accelerometer health monitor, ADIRU health monitor and solver have a modeled period of 4000 ms; the alarm aggregator, diagnosis engine and mitigation actor have a modeled period of 3000 ms. These are illustrative thread periods, not established response-time guarantees.

### ADIRU-DR-016

Retain the example's relative thread priorities: accelerometer 1 uses 2, accelerometers 2-6 use 1, accelerometer health monitoring uses 3, the solver uses 4, ADIRU health monitoring uses 5, and the alarm/diagnosis/mitigation pipeline uses 6/7/8 respectively. Interpret priority direction according to the selected runtime rather than assuming a universal scheduler convention.

### ADIRU-DR-017

Represent ARINC653 module scheduling as a separate processor-level configuration from thread periods, with four individually identifiable partition slots in the order acc, acc_hm, adirup and shm. Do not assert that the imported numeric slot budgets satisfy thread execution demands.

Qualification: The original has a 150 ms major frame and four 1 ms slots; those numbers are preserved as source observations in limitations, not certified budgets.

### ADIRU-DR-018

Retain a processor health-monitoring configuration that distinguishes deadline miss, application error, numeric error, illegal request, stack overflow, memory violation, hardware fault and power failure at process level. Recovery actions require an explicit scenario choice; the original all-ignore table is not adopted as a fault-tolerance requirement.

### ADIRU-DR-019

Associate the six accelerometer-output thread calls with C entry points acc1dataoutput through acc6dataoutput, and the accelerometer-health-monitor call with acchm_monitor, using the acc_code object-code group.

### ADIRU-DR-020

Associate the solver and ADIRU health-monitor calls with C entry points solve and adirup_hm in the adirup_code object-code group. Associate the system-health pipeline with alarm_aggregator, diagnosis_engine and mitigation_actor in the shm_code object-code group.

### ADIRU-DR-021

Keep platform adaptations as distinct extensions of the common base architecture: ADIRU::ADIRU.DeOS and ADIRU::ADIRU.VxWorks653. Select one platform variant for a deployment; do not require both platform configurations to hold in the same instance.

### ADIRU-DR-022

In the DeOS variant, identify the partition executables as adiru-acc.exe, adiru-acc-hm.exe, adiru-p.exe and adiru-shm.exe for part1 through part4. Treat its numeric partition-period and execution-time settings as unvalidated example configuration pending workload and schedule reconciliation.

### ADIRU-DR-023

In the VxWorks653 variant, identify the partition images as adiru_acc, adiru_acc_hm, adiru_p and adiru_shm for part1 through part4; preserve the shared four-process architecture and mappings inherited from the base.

### ADIRU-DR-024

Keep ADIRU::ADIRU.EMV2 as a distinct analysis extension of the base. Add six physical accelerometer device instances and refine the accelerometer process to accept six same-numbered device-input paths, rather than replacing the base emulation model for every scenario.

Qualification: Physical-device data reaches a new thread input, but that input is not connected to the inherited C subprogram call. These are structural/EMV2 paths, not an established functional device-to-simulation pipeline.

### ADIRU-DR-025

For the EMV2 variant, represent erroneous accelerometer values as an outgoing ValueErroneous propagation from each accelerometer device. Associate the device failure state with this error source and preserve the in-flight erroneous-value hazard description.

Qualification: This is an error-model declaration, not an empirically validated failure-rate or safety claim.

### ADIRU-DR-026

In the EMV2 variant, retain six separate error-propagation paths through the refined accelerometer process, from each physical-device input to the corresponding simulated-process output.

### ADIRU-DR-027

Model accelerometer-health-monitor error detection on each of its six ValueErroneous inputs, with an alarm at acc_error_out and a transition from operational to failed. The root composite error model shall expose the accelerometer-health-monitor failed state as the system failed state.

### ADIRU-DR-028

Keep the measurement-computation interface scoped to the six solver inputs in this example. The architecture does not specify a complete inertial-navigation output interface or a numerical navigation algorithm, so those functions are outside this derived requirement set.

## Scope limitations

- Current local OSATE core checks fail at adiru_memory.aadl:17 because ARINC653::Memory_Type and Code_Memory are unavailable in the installed standard library; all four root instantiations were not run. This task does not repair source or certify deployment.

- Four partition identifiers are all 1 and explicitly marked XXX in adiru_processor.aadl:24-33; those duplicate identifiers are not adopted as desired identifiers.

- The processor declares a 150 ms major frame with only one 1 ms slot per partition. DeOS adds 25 ms partition periods and 1 ms execution times. Threads declare 400..500 ms computation and 500 ms deadlines. These figures have not been reconciled and no schedulability or timing guarantee is asserted; only periodic structure and illustrative periods are restated.

- Each memory segment is 102400 bytes at base address 0, while threads individually declare 100 KByte code, 20 KByte stack and 1 KByte data. These values are not treated as a feasible memory layout or capacity requirement.

- Sampling refresh properties differ across the same flow: process measurements use 25 ms, accelerometer-thread outputs 50 ms, accelerometer-HM inputs 20 ms, and ADIRUp process inputs 1 ms. No unified freshness guarantee is inferred.

- All four virtual processors mark DAL LEVEL_A with XXX Review value; all eight processor health-monitor actions are ignore. Neither certification level nor recovery adequacy is established.

- The ADIRUp EMV2 transition considers triples among inputs 1..4 although six measurement inputs exist; it is not generalized to a six-sensor voting requirement. Device probability 3.4e-5 is an example declaration without a verified empirical basis.

- EMV2 device event-data outputs connect to process data inputs; variant compatibility and error-model semantics have not been checked because core prerequisites failed. No error-propagation analysis was run.

- Source code generation paths refer to object files and external ARINC653 runtimes. No Ocarina code generation, target execution, ABI validation, failure injection or numerical solver correctness was performed.

- The author paper/slides link in the SEI article now redirects to a generic projects page. A conference tutorial confirms the ADIRU demonstration but does not provide a version-matched independent requirements set.

- The EMV2 physical-device inputs end at added thread ports; the inherited C subprogram call is not connected to those inputs. A functional path from real sensor data to simulated outputs is not established.

## Provenance

Source project: [OpenAADL/AADLib](https://github.com/OpenAADL/AADLib/tree/c8a5c7e310b37761bb5ecc7b5ff3fc6cbd42716a/examples/adiru).
Pinned source commit: `c8a5c7e310b37761bb5ecc7b5ff3fc6cbd42716a`.

No independent natural-language requirements baseline tied to this fixed ADIRU version was located. Source comments and author publications describe architecture and demonstration goals. Every requirement here is a model-derived example or a restatement of an embedded EMV2 declaration; none is labeled an original product requirement.

Original AADLib BSD 3-Clause LICENSE and per-file notices retained.

The original mining-time commit of the A3D instance is unknown. The requirement-to-source mapping describes the pinned upstream snapshot, not a recovered historical stakeholder specification.

Line-level traceability: `example/reference/public/a3d/requirements/a3d_adiru.json`.
