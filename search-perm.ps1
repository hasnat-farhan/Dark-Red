$f = "C:\Users\hasnat\Downloads\Dark-Red\perm.txt"
adb -s RRCW902V2YY shell dumpsys package com.antor.sosblue > $f 2>&1
Select-String -Path $f -Pattern 'SEND_SMS|RECEIVE_SMS|READ_PHONE_STATE|granted=' | Select-Object -First 30
