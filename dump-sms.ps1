$path = "C:\Users\hasnat\Downloads\Dark-Red\ui-sms2.xml"
[xml]$xml = Get-Content $path -Raw
$ns = New-Object System.Xml.XmlNamespaceManager($xml.NameTable)
$ns.AddNamespace("a", "http://schemas.android.com/apk/res/android")
$root = $xml.DocumentElement
$rows = @()
function Walk($n) {
  $id = $n.GetAttribute("resource-id", "http://schemas.android.com/apk/res/android")
  $text = $n.GetAttribute("text", "http://schemas.android.com/apk/res/android")
  $cls = $n.GetAttribute("class", "http://schemas.android.com/apk/res/android")
  $checked = $n.GetAttribute("checked", "http://schemas.android.com/apk/res/android")
  $enabled = $n.GetAttribute("enabled", "http://schemas.android.com/apk/res/android")
  $click = $n.GetAttribute("clickable", "http://schemas.android.com/apk/res/android")
  $bounds = $n.GetAttribute("bounds", "http://schemas.android.com/apk/res/android")
  if ($id -or $text) {
    $script:rows += [pscustomobject]@{ id=$id; cls=$cls; text=$text; checked=$checked; enabled=$enabled; click=$click; bounds=$bounds }
  }
  foreach ($c in $n.ChildNodes) { Walk $c }
}
Walk $root
$rows | Format-Table -AutoSize -Wrap | Out-String -Width 4096
