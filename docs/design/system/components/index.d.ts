/** Zeffin components. Each function returns a DOM element; append it where it goes. */
export type StateKind = 'success' | 'warning' | 'danger' | 'action' | 'info' | '';
export interface ButtonProps { label: string; variant?: 'primary' | 'warning' | 'success' | 'quiet'; small?: boolean; disabled?: boolean; }
export interface StateTextProps { kind?: StateKind; text: string; }
export interface InfoRow { label: string; value: string; kind?: StateKind; mono?: boolean; }
export interface ActionRowProps { label: string; icon?: 'sync' | 'stop' | 'open' | 'plus' | 'pin' | 'star' | 'pencil' | 'copy'; danger?: boolean; }
export interface InfoCardProps { title?: string; state?: StateTextProps; rows?: InfoRow[]; actions?: ActionRowProps[]; }
export interface SettingRowProps { title: string; description?: string; value?: string; toggle?: boolean; on?: boolean; }
export interface TabSwitchProps { tabs: string[]; active?: number; }
export interface StatusBarProps { name: string; relay: 'active' | 'connecting' | 'stopped' | 'error'; transport?: 'BLE' | 'USB'; agent: 'active' | 'paused' | 'stopped' | 'busy'; seq?: number; screenAge?: number; }
export interface FlipperScreenProps { frame?: Uint8Array; scale?: number; stale?: boolean; staleText?: string; }
export interface GateControlsProps { state: 'active' | 'paused'; }
export interface ChatMessageProps { role: 'user' | 'assistant'; text: string; meta?: string; streaming?: boolean; }
export interface ToolCardProps { tool: string; text: string; meta?: string; frame?: Uint8Array; error?: boolean; }
export interface DeviceCardProps { name?: string; address: string; likely?: boolean; current?: boolean; }
export interface TextFieldProps { label: string; value?: string; placeholder?: string; hint?: string; error?: string; secret?: boolean; mono?: boolean; }
export interface BannerProps { kind: 'success' | 'danger' | 'warning' | 'info'; title: string; text?: string; detail?: string; }
export interface SheetProps { title: string; subtitle?: string; settings?: SettingRowProps[]; actions?: ActionRowProps[]; button?: string; height?: number; }
