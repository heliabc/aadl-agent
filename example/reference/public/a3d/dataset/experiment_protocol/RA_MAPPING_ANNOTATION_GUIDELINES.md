# Annotation Guidelines for AADL–RA Mapping Validation

This document provides instructions for validating mappings between **AADL model components** and **IoT Reference Architecture (RA) components**.
Please read this document carefully before starting the validation task.

---

## 1. Goal of the Task

The goal of this task is to **validate whether a given AADL component is correctly mapped to an IoT RA component**.

Each mapping represents a claim that: `This AADL component implements the functionality of the specified RA component.`

Your task is to assess whether this claim is **correct, incorrect, or uncertain**, based on the available information.

---

## 2. Understanding the Materials

During the task, you will have access to the following materials:

* A **Google Spreadsheet** containing the mappings to be validated
* A dedicated sheet named **`iot_ra_components_description`**, which provides:

  * Functional descriptions of all IoT RA components
  * A visual illustration of the IoT Reference Architecture
* A **link to a graphical illustration** for each mapping, showing how the AADL component relates to the RA component
* The original **AADL model files**, which can be accessed in the `AADL_models/` folder of the repository
  (the file name corresponds to the value in the `AADL_model` column)

You may freely consult all of these materials at any time during the task.

---

## 3. Definition of a Correct Mapping

A mapping should be marked as **Correct** if:

* The **primary responsibility** of the AADL component matches the functionality of the RA component
* The AADL component **clearly implements or contributes to** the role defined by the RA component
* The mapping is justified by **functional behavior**, not by naming similarity alone

### Example (Correct Mapping)

| AADL_model                               | AADL_node_name | IOT_RA_component | graphical_illustration | Validation_status | Correction_suggestion |
| ---------------------------------------- | -------------- | ---------------- | ---------------------- | ----------------- | --------------------- |
| `DHsystem_Devices_security_impl_1_aaxl2` | `DoorSensor`   | `Sensor`         | link                   | Correct           |                       |

**Explanation:**
The `DoorSensor` component is responsible for detecting physical events and producing sensor data.
This functionality directly matches the role of the `Sensor` component in the IoT Reference Architecture.
Therefore, the mapping is functionally correct.

---

## 4. Definition of an Incorrect Mapping

A mapping should be marked as **Incorrect** if:

* The AADL component and the RA component have **different or unrelated responsibilities**
* The mapping is based mainly on **similar naming**, rather than functional equivalence

### Example (Incorrect Mapping)

| AADL_model                                               | AADL_node_name      | IOT_RA_component | graphical_illustration | Validation_status | Correction_suggestion                                                                        |
| -------------------------------------------------------- | ------------------- | ---------------- | ---------------------- | ----------------- | -------------------------------------------------------------------------------------------- |
| `smart_home_arch_RemoteServer_RemoteServer_impl_1_aaxl2` | `ROUTER_CONTROLLER` | `IoTIM`          | link                   | Incorrect         | Gateway |

**Explanation:**
Although the name `ROUTER_CONTROLLER` may suggest a central role, its functionality is related to routing or control infrastructure, which maps to `Gateway` component in the IoT Reference Architecture.
Meanwhile, the RA component `IoTIM` represents IoT information or device management, which is functionally different.
As a result, this mapping is incorrect.

---

## 5. Uncertain Cases

A mapping should be marked as **Uncertain** if:

* There is **insufficient information** to confidently assess the mapping
* The AADL component has **mixed responsibilities** that do not clearly align with a single RA component

In such cases, please provide a short explanation in the **`Correction_suggestion`** field to clarify the uncertainty.

---

## 6. Common Edge Cases

Please pay special attention to the following situations:

### One-to-many relationships

A single AADL component may reasonably correspond to **multiple RA components**.

* In such cases, RA components should be **separated by “/”**
* The components should be ordered by **decreasing confidence**

**Example:**
`IP_DEVICE_CONTROLLER → Application / Device`

This means that the AADL component most likely corresponds to the `Application` RA component, but it could also correspond to `Device` with lower confidence.

### Naming ambiguity

Do **not** rely on component names alone.
Always focus on **what the component does, how it connects with other components**, rather than how it is named.

---

## 7. How to Record Validation Decisions

For each mapping in the spreadsheet:

1. Select one value in the **`Validation_status`** column:
   * `Correct`
   * `Incorrect`
   * `Uncertain`
2. If the mapping is **Incorrect** or **Uncertain**, you are encouraged (but not required) to:
   * Provide a brief explanation
   * Suggest an alternative RA component
     in the **`Correction_suggestion`** column

Short and clear comments are sufficient.

---

## 8. General Recommendations

* Work **independently**, without discussing the task with other participants
* Take short breaks if needed; there is no strict time limit
* When in doubt, prefer **Uncertain** over guessing

---

## 9. Thank You

Thank you for your time and contribution.
Your input is essential for building a **reliable and high-quality ground-truth dataset**.