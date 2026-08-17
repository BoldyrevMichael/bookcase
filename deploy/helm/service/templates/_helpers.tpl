{{/* Имя сервиса: по умолчанию — имя релиза, потому что релиз здесь и есть сервис. */}}
{{- define "service.name" -}}
{{- default .Release.Name .Values.nameOverride -}}
{{- end -}}

{{/*
Метки. Стандартный набор Kubernetes: по ним ищут поды, собирают метрики и понимают,
кто это поставил. Часть меток попадает в селектор, поэтому они вынесены отдельно —
селектор менять нельзя, он неизменяем после создания.
*/}}
{{- define "service.labels" -}}
app.kubernetes.io/name: {{ include "service.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/part-of: bookcase
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end -}}

{{- define "service.selectorLabels" -}}
app.kubernetes.io/name: {{ include "service.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}
