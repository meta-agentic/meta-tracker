import { act, fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { EpicIcon } from "./icons";
import { ListEntry } from "./ListEntry";
import { Picker, type PickerEntry } from "./Picker";

describe("a list entry: icon, label and decorator, each but the label optional", () => {
  const slots = (container: HTMLElement) => ({
    icon: container.querySelector(".vec-entry__icon"),
    label: container.querySelector(".vec-entry__label"),
    decorator: container.querySelector(".vec-entry__decorator"),
  });

  it("draws the label alone", () => {
    const { icon, label, decorator } = slots(render(<ListEntry label="Accounts" />).container);
    expect(icon).toBeNull();
    expect(label).toHaveTextContent("Accounts");
    expect(decorator).toBeNull();
  });

  it("draws a decorative icon before the label", () => {
    const { icon, label } = slots(render(<ListEntry icon={<EpicIcon />} label="Accounts" />).container);
    expect(icon).toHaveAttribute("aria-hidden", "true");
    expect(icon?.querySelector("svg")).not.toBeNull();
    expect(icon?.nextElementSibling).toBe(label);
  });

  it("draws the decorator after the label", () => {
    const { label, decorator } = slots(render(<ListEntry label="Accounts" decorator="FRM-1" />).container);
    expect(decorator).toHaveTextContent("FRM-1");
    expect(label?.nextElementSibling).toBe(decorator);
  });

  it("draws the icon alone when told to, leaving the name to the control around it", () => {
    const { icon, label, decorator } = slots(
      render(<ListEntry icon={<EpicIcon />} label="Accounts" decorator="FRM-1" show="icon" />).container,
    );
    expect(icon).not.toBeNull();
    expect(label).toBeNull();
    expect(decorator).toBeNull();
  });

  it("keeps an empty icon column when asked, so entries with and without icons align", () => {
    const { icon } = slots(render(<ListEntry label="None" reserveIcon />).container);
    expect(icon).toBeEmptyDOMElement();
  });
});

describe("the picker", () => {
  const long = "Accounts, sign-in, recovery and every other thing a person does before they can work at all";
  const entries: PickerEntry[] = [
    { value: "", label: "None" },
    { value: "FRM-1", label: long, icon: <EpicIcon />, decorator: "FRM-1" },
    { value: "FRM-2", label: "Billing", icon: <EpicIcon />, decorator: "FRM-2" },
    { value: "FRM-3", label: "Boards", icon: <EpicIcon />, decorator: "FRM-3", decoratorInName: true },
  ];
  const renderPicker = (onChange = vi.fn(), value = "") => {
    render(
      <>
        <label htmlFor="p" id="p-label">
          Parent
        </label>
        <Picker id="p" entries={entries} value={value} onChange={onChange} labelledBy="p-label" />
      </>,
    );
    return { combobox: screen.getByRole("combobox", { name: "Parent" }), onChange };
  };
  const activeOption = (combobox: HTMLElement) => document.getElementById(combobox.getAttribute("aria-activedescendant")!);

  it("names an option by its whole label, however it is cut on screen, and describes it by the decorator", () => {
    const { combobox } = renderPicker();
    fireEvent.click(combobox);
    const option = screen.getByRole("option", { name: long });
    expect(option.querySelector(".vec-entry__label")).toHaveTextContent(long);
    expect(option).toHaveAccessibleDescription("FRM-1");
  });

  it("puts the decorator in the name when the entry says so", () => {
    const { combobox } = renderPicker();
    fireEvent.click(combobox);
    expect(screen.getByRole("option", { name: "Boards FRM-3" })).toBeInTheDocument();
  });

  it("opens with Down at the chosen entry, moves with Down, End and Home, and chooses with Enter", () => {
    const { combobox, onChange } = renderPicker(vi.fn(), "FRM-1");
    act(() => combobox.focus());
    fireEvent.keyDown(combobox, { key: "ArrowDown" });
    expect(combobox).toHaveAttribute("aria-expanded", "true");
    expect(activeOption(combobox)).toHaveAccessibleName(long);
    fireEvent.keyDown(combobox, { key: "ArrowDown" });
    expect(activeOption(combobox)).toHaveAccessibleName("Billing");
    fireEvent.keyDown(combobox, { key: "End" });
    expect(activeOption(combobox)).toHaveAccessibleName("Boards FRM-3");
    fireEvent.keyDown(combobox, { key: "Home" });
    expect(activeOption(combobox)).toHaveAccessibleName("None");
    fireEvent.keyDown(combobox, { key: "ArrowUp" });
    expect(activeOption(combobox)).toHaveAccessibleName("None");
    fireEvent.keyDown(combobox, { key: "End" });
    fireEvent.keyDown(combobox, { key: "Enter" });
    expect(onChange).toHaveBeenCalledWith("FRM-3");
    expect(combobox).toHaveAttribute("aria-expanded", "false");
    expect(combobox).not.toHaveAttribute("aria-activedescendant");
  });

  it("moves to the next entry starting with a typed letter, and again on the same letter", () => {
    const { combobox } = renderPicker();
    act(() => combobox.focus());
    fireEvent.keyDown(combobox, { key: "b" });
    expect(combobox).toHaveAttribute("aria-expanded", "true");
    expect(activeOption(combobox)).toHaveAccessibleName("Billing");
    fireEvent.keyDown(combobox, { key: "b" });
    expect(activeOption(combobox)).toHaveAccessibleName("Boards FRM-3");
  });

  it("closes on Escape without choosing, and the Escape goes no further", () => {
    const { combobox, onChange } = renderPicker();
    act(() => combobox.focus());
    fireEvent.keyDown(combobox, { key: "ArrowDown" });
    fireEvent.keyDown(combobox, { key: "ArrowDown" });
    const reached = vi.fn();
    document.addEventListener("keydown", reached);
    fireEvent.keyDown(combobox, { key: "Escape" });
    expect(combobox).toHaveAttribute("aria-expanded", "false");
    expect(onChange).not.toHaveBeenCalled();
    expect(reached).not.toHaveBeenCalled();
    document.removeEventListener("keydown", reached);
  });

  it("chooses an entry by pointer, and closes on a press outside", () => {
    const { combobox, onChange } = renderPicker();
    fireEvent.click(combobox);
    fireEvent.click(screen.getByRole("option", { name: "Billing" }));
    expect(onChange).toHaveBeenCalledWith("FRM-2");
    fireEvent.click(combobox);
    fireEvent.mouseDown(document.body);
    expect(combobox).toHaveAttribute("aria-expanded", "false");
  });

  it("shows the chosen entry, icon, label and decorator, in the closed combobox", () => {
    const { combobox } = renderPicker(vi.fn(), "FRM-2");
    expect(combobox.querySelector(".vec-entry__label")).toHaveTextContent("Billing");
    expect(combobox.querySelector(".vec-entry__decorator")).toHaveTextContent("FRM-2");
    expect(combobox.querySelector(".vec-entry__icon svg")).not.toBeNull();
    expect(combobox).toHaveAccessibleName("Parent");
  });
});
