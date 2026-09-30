export interface WorkspaceNotification {
    id: number;
    message: string;
    severity: number;
}

export function createNotificationService(show: (notification: WorkspaceNotification | undefined) => void) {
    let nextId = 0;
    let currentId = 0;
    const notify = (notification: {message: string | Error; severity: number}) => {
        const id = ++nextId;
        currentId = id;
        show({id, severity: notification.severity,
            message: notification.message instanceof Error ? notification.message.message : String(notification.message)});
        return {close: () => { if (id === currentId) show(undefined); }};
    };
    return {
        notify,
        info: (message: string) => notify({message, severity: 1}),
        warn: (message: string) => notify({message, severity: 2}),
        error: (message: string | Error) => notify({message, severity: 3}),
        prompt: (severity: number, message: string) => notify({message, severity}),
        status: (message: string) => notify({message, severity: 1})
    };
}
