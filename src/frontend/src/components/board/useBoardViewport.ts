import { useLayoutEffect, useRef, useState } from "react";
import { boardViewport } from "./boardState";

// Measure the actual board start, including app header / QA banner and font zoom.
// No changes to other pages or the sidebar. Small windows use the flow fallback.
export function useBoardViewport() {
  const ref = useRef<HTMLDivElement>(null);
  const [viewport, setViewport] = useState({ height: 0, fit: false });
  useLayoutEffect(() => {
    const node = ref.current;
    const parent = node?.parentElement;
    if (!node || !parent) return;
    let frame = 0;
    const measure = () => {
      const top = node.getBoundingClientRect().top + window.scrollY;
      const padding = parseFloat(getComputedStyle(parent).paddingBottom) || 0;
      const zoom = parseFloat(getComputedStyle(document.documentElement).getPropertyValue("--app-zoom")) || 1;
      const next = boardViewport(window.innerWidth, window.innerHeight, top, zoom, node.clientWidth, padding);
      setViewport(old => old.height === next.height && old.fit === next.fit ? old : next);
    };
    const schedule = () => { cancelAnimationFrame(frame); frame = requestAnimationFrame(measure); };
    const observer = new ResizeObserver(schedule);
    observer.observe(parent); observer.observe(node);
    if (parent.parentElement) observer.observe(parent.parentElement);
    const header = parent.parentElement?.querySelector(":scope > header");
    if (header) observer.observe(header);
    const fontObserver = new MutationObserver(schedule);
    fontObserver.observe(document.documentElement, { attributes: true, attributeFilter: ["data-font-size"] });
    schedule(); window.addEventListener("resize", schedule);
    return () => { observer.disconnect(); fontObserver.disconnect(); cancelAnimationFrame(frame); window.removeEventListener("resize", schedule); };
  }, []);
  return { ref, ...viewport };
}
