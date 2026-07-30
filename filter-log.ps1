$f = "C:\Users\hasnat\Downloads\Dark-Red\logcat-final.txt"
$rx = 'ChatActivity|F2PBridge|SmsTransport|TransportMode|RadioGroup|onChecked|getSubscriberId|isAvailable|hasTelephony|MissingPermission|security|SecurityExc|Snackbar|RbSms|R.id.rb_sms'
Select-String -Path $f -Pattern $rx | Select-Object -First 60 | ForEach-Object { $_.Line }