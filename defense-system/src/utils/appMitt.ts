import mitt from '@/utils/mitt';

const emitter = mitt();

export function mittEmit(name: string, event?: any) {
    emitter.emit(name, event);
}

export function mittOn(name: string, event?: any) {
    emitter.on(name, event);
}

export function mittOff(name: string, event?: any) {
    emitter.off(name, event);
}

export function mittClear() {
    emitter.clear();
}
