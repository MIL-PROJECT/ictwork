import { useLayoutEffect, useRef, useState } from "react";

/** Available content height after the real header, padding and saved font zoom. */
export default function useViewportPanel() {
  const ref = useRef<HTMLDivElement>(null);
  const [height, setHeight] = useState(0);
  useLayoutEffect(() => {
    const node = ref.current, parent = node?.parentElement;
    if (!node || !parent) return;
    let frame = 0;
    const measure = () => {
      const zoom = Number.parseFloat(getComputedStyle(document.documentElement).getPropertyValue("--app-zoom")) || 1;
      const top = node.getBoundingClientRect().top + window.scrollY;
      const padding = Number.parseFloat(getComputedStyle(parent).paddingBottom) || 0;
      const available = Math.floor(((window.visualViewport?.height ?? window.innerHeight) - top) / zoom - padding - 1);
      setHeight(old => old === available ? old : Math.max(220, available));
    };
    const schedule = () => { cancelAnimationFrame(frame); frame = requestAnimationFrame(measure); };
    const resize = new ResizeObserver(schedule);
    resize.observe(parent); resize.observe(node);
    const header = parent.parentElement?.querySelector(":scope > header");
    if (header) resize.observe(header);
    const fonts = new MutationObserver(schedule);
    fonts.observe(document.documentElement, { attributes: true, attributeFilter: ["data-font-size"] });
    window.addEventListener("resize", schedule); window.visualViewport?.addEventListener("resize", schedule); schedule();
    return () => {
      resize.disconnect(); fonts.disconnect(); cancelAnimationFrame(frame);
      window.removeEventListener("resize", schedule); window.visualViewport?.removeEventListener("resize", schedule);
    };
  }, []);
  return { ref, height };
}
