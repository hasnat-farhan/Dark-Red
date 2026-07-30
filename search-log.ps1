$f = "C:\Users\hasnat\Downloads\Dark-Red\logcat-tap.txt"
$patterns = @(
  'ChatActivity','F2PBridge','SmsTransport','TransportMode','onCheckedChanged',
  'FATAL','smsPermission','MissingPermission','isAvailable','getSubscriberId',
  'hasTelephony','NoSuchMethod','InvalidProtocol','NoClassDef','RbSms','RadioGroup',
  'snackbar','Snack','Toast','Error','Exception','java\.','AndroidRuntime'
)
$rx = ($patterns | ForEach-Object { [regex]::Escape($_) }) -join '|'
Select-String -Path $f -Pattern $rx | ForEach-Object { $_.Line }
