// "Copy page as Markdown": fetch the page's Markdown copy and put it on the clipboard.
// One delegated listener, so it keeps working after navigation.instant swaps the page.
document.addEventListener("click", async (event) => {
  const button = event.target.closest(".bee-copy-markdown")
  if (!button) return
  event.preventDefault()
  const title = "Copy page as Markdown"
  try {
    const response = await fetch(button.dataset.markdown)
    if (!response.ok) throw new Error(`${response.status} ${response.statusText}`)
    await navigator.clipboard.writeText(await response.text())
    button.title = "Copied"
  } catch (error) {
    console.error("Copy page as Markdown failed:", error)
    button.title = "Copy failed"
  }
  setTimeout(() => { button.title = title }, 2000)
})
