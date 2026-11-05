// 날짜("2026-11-07", "2026-11-02 11:46")는 하이픈·공백에서 줄이 바뀌지 않게 한 덩어리로 둔다. 나머지 글자는 그대로 줄바꿈
const DATE = /(\d{4}-\d{2}-\d{2}(?: \d{2}:\d{2})?)/;

export default function NoBreakDates({ text }: { text: string }) {
  const parts = text.split(DATE);
  if (parts.length === 1) return text;
  return (
    <>
      {parts.map((p, i) =>
        i % 2 === 1 ? (
          <span key={i} className="whitespace-nowrap">
            {p}
          </span>
        ) : (
          p
        ),
      )}
    </>
  );
}
