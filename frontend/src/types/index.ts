export interface User { id:number; name:string; email:string; role:'ADMIN'|'SHOPKEEPER'; createdAt?:string }
export interface AuthResponse { token:string; tokenType:string; user:User }
export interface Store { id:number; storeCode:string; name:string; city:string; state:string; address:string; type:string; active:boolean; mlStoreNbr?:number|null; mlCluster?:number|null; createdAt?:string }
export interface Product { id:number; storeId:number; name:string; sku:string; category:string; description?:string; unitPrice:number; unit:string; active:boolean; createdAt?:string; mlFamilyId?:number|null; mlFamilyCode?:string|null }
export interface Inventory { id:number; storeId:number; productId:number; quantity:number; reorderLevel:number; safetyStock:number; updatedAt:string }
export interface Sale { id:number; storeId:number; productId:number; quantity:number; saleDate:string; unitPrice:number; totalAmount:number; createdAt?:string }
export interface Forecast { id:number; storeId:number; productId:number; forecastDate:string; predictedDemand:number; lowerBound:number|null; upperBound:number|null; modelVersion:string; createdAt:string }
export interface StoreFamilyForecast { id:number; storeId:number; mlFamily:string; forecastDate:string; predictionCutoff:string; forecastDemand:number; demandUnit:'MONETARY_SALES'; modelVersion:string; createdAt:string }
export interface Recommendation { id:number; storeId:number; productId:number; recommendationType:'REORDER'|'LOW_STOCK'|'OVERSTOCK'|'NORMAL'; recommendedQuantity:number; reason:string|null; recommendationDate:string; createdAt?:string }
