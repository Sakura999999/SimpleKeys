# Local payment test connection

The local website remains http://localhost:3000. Only GET /api/payments/webhook/epay is exposed through the callback-only proxy (127.0.0.1:8788); login, admin and all other routes return 404 at the public hostname.

Callback: https://sewing-chosen-packed-declare.trycloudflare.com/api/payments/webhook/epay
Return URL: http://localhost:3000/order/query (same-computer browser testing only; not a phone return URL).

Tool: S:/simplecard-dev/cloudflared.exe (downloaded from the official Cloudflare GitHub release; release SHA256 verified).
Proxy: .local-tools/payment-callback-proxy.cjs
Tunnel logs: .local-logs/cloudflared.err.log
Original URL backup: .local-backups/payment-callback-urls-before-tunnel.json

The temporary hostname stops working if cloudflared stops and changes when a new quick tunnel is created. New payment orders use the updated notify_url; older gateway orders may retain their original localhost URL. Do not repeatedly pay old QR codes.

Provider query documentation: https://www.ezfpy.cn/doc/findorder (POST /api/findorder, order_no and type=1). Standard api.php returned 404. As tested on 2026-11-17, both a provider-paid personal-channel order and a new unpaid order returned code=201 (invalid order number) through the documented endpoint. Therefore successful provider synchronization remains unverified and needs provider support. No payment is confirmed from browser redirects or user claims.

Manual Refresh now calls the provider and requires paid status, the stored trade number and matching money before durably accepting a payment event. Provider errors are visible instead of a false refresh-success toast.

Stop the temporary public entry with PowerShell:
Get-CimInstance Win32_Process -Filter "name='cloudflared.exe'" | Where-Object { $_.CommandLine -match '127.0.0.1:8788' } | ForEach-Object { Stop-Process -Id $_.ProcessId }
