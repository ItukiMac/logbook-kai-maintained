const input = document.getElementById('port');
void chrome.storage.local.get('kai2MirrorPort').then(v => { input.value = v.kai2MirrorPort ?? 8892; });
document.getElementById('save').onclick = async () => {
  const port = Number(input.value);
  const status = document.getElementById('status');
  if (!Number.isInteger(port) || port < 1024 || port > 65535 || port === 8891) {
    status.textContent = '1024〜65535の範囲で、旧版の8891とは異なる値を指定してください。'; return;
  }
  await chrome.storage.local.set({kai2MirrorPort: port});
  status.textContent = '保存しました。拡張を再読み込みしてください。';
};
