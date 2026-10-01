import {useEffect, useRef, useState} from "preact/hooks";
import * as THREE from "three";
import {OrbitControls} from "three/addons/controls/OrbitControls.js";
import {createBlockScene, type BlockScene} from "./modelRenderer";
import type {FeatureBlock} from "./configuredFeature";

interface FeatureSceneProps {
    blocks?: FeatureBlock[];
    loading: boolean;
}

interface SceneRuntime {
    scene: THREE.Scene;
    camera: THREE.PerspectiveCamera;
    controls: OrbitControls;
    handle?: BlockScene;
}

export function FeatureScene({blocks, loading}: FeatureSceneProps) {
    const host = useRef<HTMLDivElement>(null);
    const runtime = useRef<SceneRuntime>();
    const reset = useRef<() => void>();
    const [error, setError] = useState<string>();
    const [building, setBuilding] = useState(false);
    const [hasScene, setHasScene] = useState(false);

    useEffect(() => {
        const container = host.current;
        if (!container) return;
        let renderer: THREE.WebGLRenderer;
        try {
            renderer = new THREE.WebGLRenderer({antialias: true, alpha: false});
        } catch (reason) {
            setError(reason instanceof Error ? reason.message : String(reason));
            return;
        }
        renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 2));
        renderer.setClearColor(0x20252b);
        renderer.outputColorSpace = THREE.SRGBColorSpace;
        renderer.toneMapping = THREE.NoToneMapping;
        renderer.domElement.style.width = "100%";
        renderer.domElement.style.height = "100%";
        renderer.domElement.style.touchAction = "none";
        renderer.domElement.setAttribute("aria-label", "Generated configured feature in 3D");
        container.append(renderer.domElement);
        const scene = new THREE.Scene();
        const camera = new THREE.PerspectiveCamera(45, 1, 0.1, 20000);
        const controls = new OrbitControls(camera, renderer.domElement);
        controls.enableDamping = true;
        const current: SceneRuntime = {scene, camera, controls};
        runtime.current = current;
        const resize = () => {
            const width = Math.max(container.clientWidth, 1);
            const height = Math.max(container.clientHeight, 1);
            renderer.setSize(width, height, false);
            camera.aspect = width / height;
            camera.updateProjectionMatrix();
        };
        const observer = new ResizeObserver(resize);
        observer.observe(container);
        resize();
        renderer.setAnimationLoop(() => {
            controls.update();
            current.handle?.sortTranslucent(camera);
            renderer.render(scene, camera);
        });
        return () => {
            runtime.current = undefined;
            reset.current = undefined;
            observer.disconnect();
            controls.dispose();
            renderer.setAnimationLoop(null);
            current.handle?.dispose();
            renderer.dispose();
            renderer.forceContextLoss();
            renderer.domElement.remove();
        };
    }, []);

    useEffect(() => {
        const current = runtime.current;
        if (!blocks || !current) return;
        let cancelled = false;
        setError(undefined);
        setBuilding(true);
        void (async () => {
            try {
                const next = await createBlockScene(blocks, () => cancelled);
                if (!next) {
                    if (!cancelled) setBuilding(false);
                    return;
                }
                if (cancelled) { next.dispose(); return; }
                const {scene, camera, controls} = current;
                const previous = current.handle;
                scene.add(next.group);
                if (previous) scene.remove(previous.group);
                current.handle = next;
                previous?.dispose();
                const bounds = next.bounds.isEmpty()
                    ? new THREE.Box3(new THREE.Vector3(-8, -8, -8), new THREE.Vector3(8, 8, 8)) : next.bounds;
                const center = bounds.getCenter(new THREE.Vector3());
                const radius = Math.max(bounds.getSize(new THREE.Vector3()).length() / 2, 16);
                controls.minDistance = 8;
                controls.maxDistance = radius * 12;
                const fit = () => {
                    const verticalFov = THREE.MathUtils.degToRad(camera.fov);
                    const horizontalFov = 2 * Math.atan(Math.tan(verticalFov / 2) * camera.aspect);
                    const distance = radius / Math.sin(Math.min(verticalFov, horizontalFov) / 2) * 1.1;
                    camera.position.copy(center).add(new THREE.Vector3(1, 0.7, 1).normalize().multiplyScalar(distance));
                    controls.target.copy(center);
                    controls.update();
                };
                reset.current = fit;
                if (!previous) fit();
                setHasScene(true);
                setBuilding(false);
            } catch (reason) {
                if (!cancelled) {
                    setError(reason instanceof Error ? reason.message : String(reason));
                    setBuilding(false);
                }
            }
        })();
        return () => { cancelled = true; };
    }, [blocks]);

    return <>
        <div class="relative h-[min(55vh,480px)] min-h-72 overflow-hidden rounded border border-white/10 bg-[#20252b]">
            <div ref={host} class="h-full w-full"/>
            {hasScene && <button type="button" class="absolute right-2 top-2 rounded bg-black/50 px-2 py-1 hover:bg-black/70"
                                 onClick={() => reset.current?.()}>Reset view</button>}
        </div>
        <div class="mt-2 h-1 overflow-hidden rounded bg-white/5">
            {(loading || building) && <div role="progressbar" aria-label="Loading feature preview"
                class="h-full w-full animate-pulse bg-violet-500"/>}
        </div>
        {error && <p role="alert" class="mt-2 text-amber-300">3D preview unavailable: {error}</p>}
        <p class="mt-2 text-zinc-500">Drag to orbit · Scroll to zoom · Right-drag to pan</p>
    </>;
}
